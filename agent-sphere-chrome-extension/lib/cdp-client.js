/**
 * CdpClient — single wrapper around chrome.debugger (CDP).
 *
 * Reliability contract (mirrors webbrain's cdp-client):
 *  - per-tab session map; `onDetach` atomically clears the tab's session so a
 *    detached tab self-heals on the next command instead of throwing forever.
 *  - `sendCommand` on a tab without a session throws `Debugger is not attached
 *    to the tab <id>` (structured, not a silent null).
 *  - all operations are serialized through a promise queue so attach/evaluate/
 *    detach never interleave across call sites.
 *  - `evaluate` retries exactly once on detach races (never on page exceptions).
 */
export class CdpClient {
  constructor() {
    this.sessions = new Map();
    this._queue = Promise.resolve();

    chrome.debugger.onDetach.addListener((source) => {
      if (source && source.tabId != null && this.sessions.has(source.tabId)) {
        this.sessions.delete(source.tabId);
      }
    });
  }

  _attach(tabId) {
    return new Promise((resolve, reject) => {
      chrome.debugger.attach({ tabId }, '1.3', () => {
        if (chrome.runtime.lastError) {
          reject(new Error(chrome.runtime.lastError.message));
          return;
        }
        this.sessions.set(tabId, { tabId });
        resolve();
      });
    });
  }

  async attach(tabId) {
    if (this.sessions.has(tabId)) return;
    try {
      await this._attach(tabId);
    } catch (e) {
      if (String(e.message).includes('not attached')) {
        this.sessions.delete(tabId);
      }
      throw e;
    }
  }

  async detach(tabId) {
    this.sessions.delete(tabId);
    await new Promise((resolve) => {
      try {
        chrome.debugger.detach({ tabId }, () => resolve());
      } catch {
        resolve();
      }
    });
  }

  sendCommand(tabId, method, params = {}) {
    if (!this.sessions.has(tabId)) {
      return Promise.reject(new Error(`Debugger is not attached to the tab ${tabId}`));
    }
    return new Promise((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        reject(new Error(`CDP command timed out: ${method}`));
      }, 15000);
      chrome.debugger.sendCommand({ tabId }, method, params, (result) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        const err = chrome.runtime.lastError;
        if (err) {
          reject(new Error(err.message || String(err)));
          return;
        }
        resolve(result);
      });
    });
  }

  _serialized(fn) {
    const p = this._queue.then(fn, fn);
    this._queue = p.catch(() => {});
    return p;
  }

  async _evaluateOnce(tabId, expression) {
    await this.attach(tabId);
    const { result } = await this.sendCommand(tabId, 'Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
      userGesture: true,
    });
    if (result && result.exceptionDetails) {
      const ex = result.exceptionDetails.exception;
      throw new Error(
        (ex && (ex.description || ex.value)) || result.exceptionDetails.text || 'JS exception',
      );
    }
    return result;
  }

  _toResult(result) {
    if (result && result.unserializableValue) {
      // e.g. a DOM node / function with returnByValue:true → not JSON-serializable.
      return {
        success: true,
        data: `<unserializable:${result.type || 'object'}>`,
        _resultType: result.type || 'object',
        method: 'debugger',
        warning: 'evaluation succeeded but the value is not serializable',
      };
    }
    const value = result && result.value;
    if (value === undefined) {
      return { success: true, data: '__NO_RETURN__', _resultType: 'void', method: 'debugger' };
    }
    return { success: true, data: value, _resultType: typeof value, method: 'debugger' };
  }

  async evaluate(tabId, expression) {
    return this._serialized(async () => {
      try {
        return this._toResult(await this._evaluateOnce(tabId, expression));
      } catch (e) {
        const msg = String(e.message);
        // Detach raced mid-flight → drop the stale session and retry exactly once.
        if (msg.includes('not attached')) {
          this.sessions.delete(tabId);
          return this._toResult(await this._evaluateOnce(tabId, expression));
        }
        // "Another debugger is already attached" (DevTools open on the tab): not recoverable here.
        throw e;
      }
    });
  }

  /** 受信鼠标点击（CDP Input）：合成事件在严格 SPA 下常不触发
   *  Vue/React 处理器；Input.dispatchMouseEvent 走真实输入管线（isTrusted=true）。
   *  x/y 为主 frame 视口 CSS 像素（见 content-locator 的 mainFramePoint）。 */
  async nativeClick(tabId, x, y) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
          type: 'mousePressed', x, y, button: 'left', clickCount: 1,
        });
        await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
          type: 'mouseReleased', x, y, button: 'left', clickCount: 1,
        });
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.nativeClick(tabId, x, y));
      }
    });
  }

  /** 受信键盘事件（CDP Input.dispatchKeyEvent）。key 如 Enter/Escape。 */
  async nativeKeyPress(tabId, key, code, keyCode) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        await this.sendCommand(tabId, 'Input.dispatchKeyEvent', {
          type: 'rawKeyDown', key, code,
          windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode,
        });
        await this.sendCommand(tabId, 'Input.dispatchKeyEvent', {
          type: 'keyUp', key, code,
          windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode,
        });
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.nativeKeyPress(tabId, key, code, keyCode));
      }
    });
  }

  /** 受信文本输入（CDP Input.insertText）：触发输入框自身事件，trusted（含 IME/中文）。 */
  async insertText(tabId, text) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        await this.sendCommand(tabId, 'Input.insertText', { text });
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.insertText(tabId, text));
      }
    });
  }

  /** 受信清空当前输入框（Ctrl+A 全选 + 删除）。insertText 只会追加，
   *  因此整字段替换前必须先清空（对 iframe 内输入框同样有效）。 */
  async clearInput(tabId) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        await this.sendCommand(tabId, 'Input.dispatchKeyEvent', {
          type: 'keyDown', key: 'a', code: 'KeyA', modifiers: 2,
          windowsVirtualKeyCode: 65, nativeVirtualKeyCode: 65, commands: ['selectAll'],
        });
        await this.sendCommand(tabId, 'Input.dispatchKeyEvent', {
          type: 'keyUp', key: 'a', code: 'KeyA', modifiers: 2,
          windowsVirtualKeyCode: 65, nativeVirtualKeyCode: 65,
        });
        await this.sendCommand(tabId, 'Input.dispatchKeyEvent', {
          type: 'keyDown', key: 'Backspace', code: 'Backspace',
          windowsVirtualKeyCode: 8, nativeVirtualKeyCode: 8, commands: ['delete'],
        });
        await this.sendCommand(tabId, 'Input.dispatchKeyEvent', {
          type: 'keyUp', key: 'Backspace', code: 'Backspace',
          windowsVirtualKeyCode: 8, nativeVirtualKeyCode: 8,
        });
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.clearInput(tabId));
      }
    });
  }

  /** 受信悬停（CDP mouseMoved）：触发 hover 面板。 */
  async nativeHover(tabId, x, y) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
          type: 'mouseMoved', x, y, button: 'none', clickCount: 0,
        });
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.nativeHover(tabId, x, y));
      }
    });
  }

  _retryDetachOnce(tabId, e, rerun) {
    const msg = String(e && e.message);
    if (msg.includes('not attached')) {
      this.sessions.delete(tabId);
      return rerun();
    }
    throw e;
  }
}

export const cdpClient = new CdpClient();

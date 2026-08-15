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
      chrome.debugger.sendCommand({ tabId }, method, params, (result) => {
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
}

export const cdpClient = new CdpClient();

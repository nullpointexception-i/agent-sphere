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
 *  - every queued operation carries its own deadline so a hung page command can
 *    never poison the queue for every later operation.
 */

// Default operation deadline: input calls (dispatchMouseEvent/insertText/dispatchKeyEvent) are millisecond-level anyway,
// and 15s is a very generous fallback. When executeJS goes through evaluate() a larger value is passed explicitly.
const CDP_OP_TIMEOUT_MS = 15000;

// ---- Humanization parameters (technical side; business pacing is defined by the skill, this only provides "real input" and hard floors) ----
// Exact landing: no more pixel jitter on element coordinates. The former +/-CLICK_JITTER_PX made small targets
// (checkboxes/small icons) miss - the on-screen cursor is drawn at the exact target point, so the click must land on that same point.
// Humanization keeps only the "movement" layer: start offset + vector path (see nativeClick / _moveMouse).
// Vector path steps and per-step interval: about 8 steps x 8~22ms ~= 100~180ms, which looks like "moving there" rather than teleporting.
const TRAJECTORY_STEPS = 8;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const randInt = (min, max) => min + Math.floor(Math.random() * (max - min + 1));

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

  _serialized(fn, timeoutMs = CDP_OP_TIMEOUT_MS) {
    // Queue poisoning protection: if fn never settles (a while(true) running in the page or a long-hanging fetch),
    // this._queue stays pending forever and every subsequent CDP operation (click/insertText/hitTest/screenshot)
    // queues up without running until the service worker restarts. Each operation gets its own deadline here
    // so the queue keeps flowing.
    // Note: a timeout cannot cancel a CDP command already running in the page (CDP has no cancel semantics),
    // but it is enough to keep later commands from being blocked by this one hanging.
    const guarded = () => {
      let timer;
      return Promise.race([
        Promise.resolve().then(fn),
        new Promise((_, reject) => {
          timer = setTimeout(
            () => reject(new Error('CDP operation timed out after ' + timeoutMs + 'ms')),
            timeoutMs,
          );
        }),
      ]).finally(() => clearTimeout(timer)); // both paths must clean up, otherwise timers pile up
    };
    const p = this._queue.then(guarded, guarded);
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

  /** CDP-layer entry for executeJS (the only caller is background's executeJsOnTab).
   *  timeoutMs is supplied by the caller and must be less than the backend executeJS timeout, so the extension can return a clean TIMEOUT first. */
  async evaluate(tabId, expression, timeoutMs = CDP_OP_TIMEOUT_MS) {
    return this._serialized(async () => {
      try {
        // Wrap it: also capture document.title when nothing is returned (side-effect verification trick),
        // since __NO_RETURN__ means "executed successfully but returned nothing" - do not retry it as a failure.
        const expr =
          '(async () => { const __asR = await (async () => { ' +
          expression +
          '\n })(); return { __asV: __asR === void 0 ? "__AS_VOID__" : __asR, __asT: (typeof document !== "undefined" ? document.title : "") }; })()';
        return this._unwrap(await this._evaluateOnce(tabId, expr));
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
    }, timeoutMs);
  }

  // Unwrap evaluate's void-title wrapper: no return value -> __NO_RETURN__ + _documentTitle; normal values pass through untouched.
  _unwrap(raw) {
    if (!raw) return { success: true, data: '__NO_RETURN__', _resultType: 'void', method: 'debugger' };
    if (raw.unserializableValue) return this._toResult(raw);
    const v = raw.value;
    if (v && typeof v === 'object' && v.__asV === '__AS_VOID__') {
      return {
        success: true,
        data: '__NO_RETURN__',
        _resultType: 'void',
        method: 'debugger',
        warning: 'evaluation succeeded but returned no value (__NO_RETURN__ is expected); if side effects were flagged via document.title, verify with _documentTitle',
        _documentTitle: v.__asT,
      };
    }
    if (v && typeof v === 'object' && Object.prototype.hasOwnProperty.call(v, '__asV')) {
      return this._toResult({ ...raw, value: v.__asV });
    }
    return this._toResult(raw);
  }

  /** Hit verification: report the description of the element actually hit at the given CSS coordinate (<tag#id.class>).
   *  Works in main-viewport coordinate space, so it also covers cross-origin OOPIFs - the content side's
   *  elementFromPoint only knows its own iframe coordinate space and cannot reach across origins.
   *  Corresponds to Codex's DOM.getNodeForLocation + DOM.describeNode hit comparison.
   *  Read-only probe; any probe failure returns hit:false and never blocks the caller's fallback flow. */
  async hitTest(tabId, x, y) {
    try {
      await this.attach(tabId);
      const loc = await this.sendCommand(tabId, 'DOM.getNodeForLocation', {
        x: Math.round(x), y: Math.round(y), includeUserAgentShadowDOM: true,
      });
      if (!loc || !loc.backendNodeId) return { hit: false, desc: '', backendNodeId: null };
      let tag = '';
      let id = '';
      let cls = '';
      try {
        const described = await this.sendCommand(tabId, 'DOM.describeNode', { backendNodeId: loc.backendNodeId });
        const node = described && described.node;
        if (node) {
          tag = String(node.localName || node.nodeName || '').toLowerCase();
          const attrs = Array.isArray(node.attributes) ? node.attributes : [];
          for (let i = 0; i < attrs.length; i += 2) {
            if (attrs[i] === 'id' && !id) id = attrs[i + 1] || '';
            if (attrs[i] === 'class' && !cls) cls = attrs[i + 1] || '';
          }
        }
      } catch (e) {
        // describeNode failed: still report the hit, just with an incomplete description
      }
      const clsOut = cls.trim() ? '.' + cls.trim().split(/\s+/).slice(0, 3).join('.') : '';
      return {
        hit: true,
        backendNodeId: loc.backendNodeId,
        desc: `<${tag || 'unknown'}${id ? '#' + id : ''}${clsOut}>`,
      };
    } catch (e) {
      return { hit: false, desc: '', backendNodeId: null };
    }
  }

  /**
   * Trusted mouse movement path (multiple CDP mouseMoved events).
   *
   * <p>A real user's mouse "moves there", producing a series of mouseMoved events before the press. The earlier implementation sent only
   * press/release, with **no mouseMoved at all** - one of the most typical automation fingerprints.
   * This uses an easeOutCubic approximation plus jitter on intermediate points, with the last step landing exactly on the target.
   */
  async _moveMouse(tabId, fromX, fromY, toX, toY, steps = TRAJECTORY_STEPS) {
    for (let i = 1; i <= steps; i++) {
      const t = i / steps;
      const e = 1 - Math.pow(1 - t, 3); // easeOutCubic
      const last = i === steps;
      const jx = last ? 0 : (Math.random() - 0.5) * 2.5;
      const jy = last ? 0 : (Math.random() - 0.5) * 2.5;
      await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
        type: 'mouseMoved',
        x: Math.round(fromX + (toX - fromX) * e + jx),
        y: Math.round(fromY + (toY - fromY) * e + jy),
        button: 'none',
        clickCount: 0,
      });
      await sleep(randInt(8, 22));
    }
  }

  /** Trusted mouse click (CDP Input): synthetic events often fail to trigger
   *  Vue/React handlers in strict SPAs; Input.dispatchMouseEvent goes through the real input pipeline (isTrusted=true).
   *  x/y are main-frame viewport CSS pixels (see mainFramePoint in content-locator).
   *
   *  <p>Humanization: first a mouseMoved path -> random offset on landing -> random delay between press/release.
   *  None of this changes the "trusted input" nature; it only adds the event sequence a real mouse would produce. */
  async nativeClick(tabId, x, y) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        // Exact landing: click element coordinates directly, no pixel jitter (see the humanization parameters at the top of the file).
        const tx = Math.max(0, x);
        const ty = Math.max(0, y);
        // The start point is near the target (simulating "moving over from the previous nearby position"), with no extra viewport size query
        const sx = Math.max(0, tx + randInt(-120, 120));
        const sy = Math.max(0, ty + randInt(-90, 90));
        await this._moveMouse(tabId, sx, sy, tx, ty);
        await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
          type: 'mousePressed', x: tx, y: ty, button: 'left', clickCount: 1,
        });
        await sleep(randInt(35, 120)); // a real key press has a duration, not 0ms
        await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
          type: 'mouseReleased', x: tx, y: ty, button: 'left', clickCount: 1,
        });
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.nativeClick(tabId, x, y));
      }
    });
  }

  /** Trusted keyboard event (CDP Input.dispatchKeyEvent). key such as Enter/Escape. */
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

  /** Trusted text input (CDP Input.insertText): fires the input's own events, trusted (including IME/CJK).
   *
   *  <p>Character-by-character input: a real user types one character at a time, and "inserting the whole block at once" is a mechanical fingerprint.
   *  It works for CJK too (insertText goes through the real input pipeline). To keep long text from exceeding type's 10s timeout,
   *  the per-character interval adapts to the total budget (3.5s by default) with +/-30% jitter. */
  async typeText(tabId, text, opts = {}) {
    const s = text == null ? '' : String(text);
    const chars = Array.from(s); // handles surrogate pairs / CJK correctly
    const budgetMs = opts.budgetMs || 3500;
    const perChar = chars.length > 0
      ? Math.max(12, Math.min(90, Math.floor(budgetMs / chars.length)))
      : 0;
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        for (const ch of chars) {
          await this.sendCommand(tabId, 'Input.insertText', { text: ch });
          const jitter = perChar * (0.7 + Math.random() * 0.6);
          await sleep(Math.round(jitter));
        }
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.typeText(tabId, text, opts));
      }
    });
  }

  /** Trusted text input (the whole block at once). Kept for cases where character-by-character is unsuitable/too slow and for existing callers. */
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

  /** Trusted clearing of the current input (Ctrl+A select all + delete). insertText only appends,
   *  so the field must be cleared before replacing it entirely (works for inputs inside iframes too). */
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

  /** Trusted hover (CDP mouseMoved): triggers hover panels.
   *  Humanization: move along a short path before hovering, avoiding "the pointer appears on the target out of nowhere". */
  async nativeHover(tabId, x, y) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        const sx = Math.max(0, x + randInt(-80, 80));
        const sy = Math.max(0, y + randInt(-60, 60));
        await this._moveMouse(tabId, sx, sy, x, y, 6);
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.nativeHover(tabId, x, y));
      }
    });
  }

  /** Viewport size (CSS pixels); returns {} when unavailable, leaving the fallback to the caller. */
  async _viewportSize(tabId) {
    try {
      const { result } = await this.sendCommand(tabId, 'Runtime.evaluate', {
        expression: '({w: window.innerWidth, h: window.innerHeight})',
        returnByValue: true,
      });
      return (result && result.value) || {};
    } catch {
      return {};
    }
  }

  /**
   * Page-level scrolling: CDP `Input.dispatchMouseEvent{type:'mouseWheel'}`, **segmented + random pauses**.
   *
   * <p>Replaces the content script's `window.scrollBy`: that is "the script moved the page" and produces **no scroll events** -
   * "the position changed but no wheel event was received" is itself an automation signal. This emits real (isTrusted) wheel events,
   * lets Chrome scroll on its own, and scrolls segment by segment like a human.
   */
  async scrollPage(tabId, direction, amount) {
    const dir = direction || 'down';
    const vertical = dir !== 'left' && dir !== 'right';
    const sign = (dir === 'up' || dir === 'left') ? -1 : 1;
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        const size = await this._viewportSize(tabId);
        const cx = Math.round((size.w || 800) / 2);
        const cy = Math.round((size.h || 600) / 2);
        const total = amount || Math.max(Math.round((size.h || 600) * 0.8), 200);
        const segments = Math.max(2, Math.min(6, Math.round(total / 220)));
        const per = Math.round(total / segments);
        for (let i = 0; i < segments; i++) {
          await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
            type: 'mouseWheel',
            x: cx,
            y: cy,
            deltaX: vertical ? 0 : sign * per,
            deltaY: vertical ? sign * per : 0,
          });
          await sleep(randInt(60, 180)); // random pause between segments
        }
      } catch (e) {
        this._retryDetachOnce(tabId, e, () => this.scrollPage(tabId, direction, amount));
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

  /** Screenshot (CDP Page.captureScreenshot, the only debugger entry point).
   *  fullPage uses captureBeyondViewport to capture the whole page; the result contains base64, CSS viewport size and dpr
   *  (clickAt's x/y are device pixels; use dpr to convert to CSS px). */
  async captureScreenshot(tabId, { format = 'jpeg', quality = 60, fullPage = false } = {}) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        const params = { format, quality };
        if (fullPage) params.captureBeyondViewport = true;
        const { data } = await this.sendCommand(tabId, 'Page.captureScreenshot', params);
        if (!data) {
          throw new Error('No screenshot data returned');
        }
        // Send Runtime.evaluate directly with sendCommand to avoid evaluate() re-enqueueing and self-locking
        let w = 0, h = 0, dpr = 1;
        try {
          const ev = await this.sendCommand(tabId, 'Runtime.evaluate', {
            expression:
              '({ w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio || 1 })',
            returnByValue: true,
            awaitPromise: true,
          });
          const v = ev && ev.result && ev.result.value;
          if (v) {
            w = v.w || 0;
            h = v.h || 0;
            dpr = v.dpr || 1;
          }
        } catch (e) {
          console.warn('[CdpClient] viewport probe failed, fallback 0/0', e?.message);
        }
        return {
          base64: data,
          contentType: format === 'png' ? 'image/png' : 'image/jpeg',
          width: Math.round(w * dpr),
          height: Math.round(h * dpr),
          viewportWidth: w,
          viewportHeight: h,
          dpr,
        };
      } catch (e) {
        const msg = String(e && e.message);
        if (msg.includes('not attached')) {
          this.sessions.delete(tabId);
          throw new Error('Screenshot failed: debugger detached');
        }
        throw e;
      }
    });
  }
}

export const cdpClient = new CdpClient();

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

// 默认操作 deadline：输入类（dispatchMouseEvent/insertText/dispatchKeyEvent）本就毫秒级，
// 15s 是极宽松的兜底。executeJS 走 evaluate() 时单独传更大的值。
const CDP_OP_TIMEOUT_MS = 15000;

// ---- 拟人化参数（技术侧；业务节奏由 skill 定义，这里只做"真实输入"与硬地板）----
// 落点精确：不再对元素坐标做像素抖动。曾经的 ±CLICK_JITTER_PX 会让小目标
// （勾选框/小图标）点空 —— 屏幕光标画在精确目标点，点击就必须落在同一点。
// 拟人化只保留"移动"层：起点偏移 + 向量轨迹（见 nativeClick / _moveMouse）。
// 向量轨迹的步数与每步间隔：约 8 步 × 8~22ms ≈ 100~180ms，肉眼是"移过去"而非瞬移。
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
    // 防队列中毒：若 fn 永不 settle（页面里跑了 while(true) 或长挂起的 fetch），
    // this._queue 会永久 pending，之后所有 CDP 操作（click/insertText/hitTest/截图）
    // 全部排队不执行，直到 service worker 重启。这里给每次操作加独立 deadline，
    // 让队列始终能继续流动。
    // 注意：超时并不能取消页面里已在跑的 CDP 命令（CDP 无取消语义），
    // 但足以保证后续命令不被这一次挂起堵死。
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
      ]).finally(() => clearTimeout(timer)); // 两条路径都要清理，否则定时器会堆积
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

  /** executeJS 的 CDP 层入口（唯一调用方是 background 的 executeJsOnTab）。
   *  timeoutMs 由调用方给出，必须小于后端 executeJS 超时，扩展才能先返回干净的 TIMEOUT。 */
  async evaluate(tabId, expression, timeoutMs = CDP_OP_TIMEOUT_MS) {
    return this._serialized(async () => {
      try {
        // 包一层：无返回值时一并捕获 document.title（副作用验证技巧），
        // __NO_RETURN__ 属"执行成功但无返回值"，勿当失败重试。
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

  // 解开 evaluate 的 void-title 包装：未返回值 → __NO_RETURN__ + _documentTitle；正常值原样透传。
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
        warning: 'evaluation succeeded but returned no value（__NO_RETURN__ 属正常）；副作用已用 document.title 标记的话，读 _documentTitle 复核',
        _documentTitle: v.__asT,
      };
    }
    if (v && typeof v === 'object' && Object.prototype.hasOwnProperty.call(v, '__asV')) {
      return this._toResult({ ...raw, value: v.__asV });
    }
    return this._toResult(raw);
  }

  /** 命中校验：报告该 CSS 坐标处实际命中的元素描述（<tag#id.class>）。
   *  在主视口坐标空间工作，因此跨源 OOPIF 也能覆盖 —— content 侧的
   *  elementFromPoint 只知道自己 iframe 的坐标空间，跨源时够不着。
   *  对应 Codex 的 DOM.getNodeForLocation + DOM.describeNode 命中比对。
   *  只读探测，探测失败一律返回 hit:false，绝不阻断调用方的降级流程。 */
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
        // describeNode 失败：仍然报告命中，只是描述不完整
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
   * 受信鼠标移动轨迹（CDP mouseMoved 多点）。
   *
   * <p>真实用户的鼠标是"移过去"的，会先产生一串 mouseMoved 才按下。早先的实现只发
   * press/release，**没有任何 mouseMoved** —— 这是最典型的自动化指纹之一。
   * 这里用 easeOutCubic 逼近 + 中间点抖动，最后一步精确落到目标点。
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

  /** 受信鼠标点击（CDP Input）：合成事件在严格 SPA 下常不触发
   *  Vue/React 处理器；Input.dispatchMouseEvent 走真实输入管线（isTrusted=true）。
   *  x/y 为主 frame 视口 CSS 像素（见 content-locator 的 mainFramePoint）。
   *
   *  <p>拟人化：先走一段 mouseMoved 轨迹 → 落点加随机偏移 → press/release 之间随机延迟。
   *  这些都不改变"可信输入"的本质，只是补上真实鼠标本就会产生的事件序列。 */
  async nativeClick(tabId, x, y) {
    return this._serialized(async () => {
      try {
        await this.attach(tabId);
        // 落点精确：直接点元素坐标，不做像素抖动（见文件头拟人化参数说明）。
        const tx = Math.max(0, x);
        const ty = Math.max(0, y);
        // 起点在目标附近（模拟"从旁边的上一次位置移过来"），无需额外查询视口尺寸
        const sx = Math.max(0, tx + randInt(-120, 120));
        const sy = Math.max(0, ty + randInt(-90, 90));
        await this._moveMouse(tabId, sx, sy, tx, ty);
        await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
          type: 'mousePressed', x: tx, y: ty, button: 'left', clickCount: 1,
        });
        await sleep(randInt(35, 120)); // 真实按键有按压时长，不是 0ms
        await this.sendCommand(tabId, 'Input.dispatchMouseEvent', {
          type: 'mouseReleased', x: tx, y: ty, button: 'left', clickCount: 1,
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

  /** 受信文本输入（CDP Input.insertText）：触发输入框自身事件，trusted（含 IME/中文）。
   *
   *  <p>逐字输入：真实用户是一个字一个字敲的，"一次性插入整段"是机械指纹。
   *  对 CJK 同样有效（insertText 走真实输入管线）。为避免长文本超出 type 的 10s 超时，
   *  按总预算（默认 3.5s）自适应每字间隔，并加 ±30% 抖动。 */
  async typeText(tabId, text, opts = {}) {
    const s = text == null ? '' : String(text);
    const chars = Array.from(s); // 正确处理代理对 / CJK
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

  /** 受信文本输入（整段一次性）。保留给"逐字"不适用/过慢的场景与既有调用方。 */
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

  /** 受信悬停（CDP mouseMoved）：触发 hover 面板。
   *  拟人化：悬停前先走一小段轨迹，避免"指针凭空出现在目标上"。 */
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

  /** 视口尺寸（CSS 像素）；取不到时返回 {}，由调用方兜底。 */
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
   * 页面级滚动：CDP `Input.dispatchMouseEvent{type:'mouseWheel'}`，**分段 + 随机停顿**。
   *
   * <p>替代内容脚本的 `window.scrollBy`：后者是"脚本把页面挪了"，**不产生任何滚动事件**——
   * "位置变了却收不到 wheel 事件"本身就是一个自动化信号。这里发出真实（isTrusted）滚轮事件，
   * 由 Chrome 自己滚动，并像真人那样一段一段滚。
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
          await sleep(randInt(60, 180)); // 段间随机停顿
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

  /** 截图（CDP Page.captureScreenshot，唯一 debugger 入口）。
   *  fullPage 用 captureBeyondViewport 截全页；返回值含 base64、CSS 视口尺寸与 dpr
   *  （clickAt 的 x/y 是设备像素，换算 CSS px 用 dpr）。 */
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
        // 直接用 sendCommand 发 Runtime.evaluate，避免 evaluate() 二次入队自锁
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

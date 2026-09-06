/**
 * Content-script locator helpers (ISOLATED world).
 * Loaded before content.js; attaches to the shared window.__asContent namespace.
 *
 * Borrows Ui.Vision's dom_utils ideas: text glob matching (with `*`/`?`
 * wildcards), XPath queries, and a final aria-label lookup.
 */
window.__asContent = window.__asContent || {};

(function () {
  const AS = window.__asContent;

  // Convert a glob pattern (`*`, `?`) into a case-insensitive RegExp.
  AS.globToRegExp = function (pattern, flags) {
    const escaped = String(pattern)
      .replace(/[.+^${}()|[\]\\]/g, '\\$&')
      .replace(/\*/g, '.*')
      .replace(/\?/g, '.');
    return new RegExp('^' + escaped + '$', flags || 'i');
  };

  AS.globMatch = function (pattern, value) {
    if (!pattern) return false;
    return AS.globToRegExp(pattern).test(String(value || ''));
  };

  // 元素所在 window（跨同源 iframe 时用其自身文档的 window，保证坐标/样式一致）。
  function ownerWin(el) {
    return el && el.ownerDocument && el.ownerDocument.defaultView
      ? el.ownerDocument.defaultView
      : window;
  }

  // 同源 iframe + open shadow DOM 的文档收集（顺序：主文档 → 遇到的 iframe/contentDocument）。
  // frameId=0 → 仅顶层 document（弹层常渲染在顶层 dialog，忽略 iframe 以减少噪音/歧义）。
  function collectFrameDocs(scope, frameId) {
    const docs = [];
    const seenDocs = new Set();
    const topOnly = frameId === 0;
    const pick = (root) => {
      if (topOnly) {
        if (root === document) {
          seenDocs.add(root);
          docs.push(root);
        }
        return;
      }
      if (!seenDocs.has(root)) {
        seenDocs.add(root);
        docs.push(root);
      }
    };
    const reachable = [];
    const discover = (root) => {
      if (!root || reachable.includes(root)) return;
      reachable.push(root);
      for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) discover(el.shadowRoot);
        if (el.tagName === 'IFRAME') {
          try {
            if (el.contentDocument) discover(el.contentDocument);
          } catch (e) { /* 跨源 iframe 跳过 */ }
        }
      }
    };
    const walk = (root) => {
      pick(root);
      if (topOnly) return; // 顶层只取 document 本体，不往下钻 iframe/shadow
      for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) walk(el.shadowRoot);
        if (el.tagName === 'IFRAME') {
          try {
            if (el.contentDocument) walk(el.contentDocument);
          } catch (e) { /* 跨源 iframe 跳过 */ }
        }
      }
    };
    discover(document);
    if (!scope) {
      if (topOnly) {
        if (!seenDocs.has(document)) {
          seenDocs.add(document);
          docs.push(document);
        }
        return docs;
      }
      walk(document);
      return docs;
    }
    if (topOnly) {
      // 顶层 + scope：仅当顶层包含 scope 容器时纳入（iframe/shadow 不穿透）
      let hit = false;
      for (const root of reachable) {
        try {
          if (root === document && root.matches && root.matches(scope)) hit = true;
          if (!hit && root === document && root.querySelector(scope)) hit = true;
        } catch (e) { /* ignore */ }
      }
      if (hit) {
        seenDocs.add(document);
        docs.push(document);
      }
      return docs;
    }
    // scope 可能位于同源 iframe 或 ShadowRoot，不能只从顶层 document 查询。
    for (const root of reachable) {
      try {
        if (root.matches && root.matches(scope)) walk(root);
        root.querySelectorAll(scope).forEach((match) => walk(match));
      } catch (e) { /* 非法 scope 或文档正在导航时忽略 */ }
    }
    return docs;
  }

  AS.collectFrameDocs = collectFrameDocs;

  // 逐文档（主文档 + 同源 iframe + shadow）按 selector 收集元素，DOM 顺序稳定，
  // 与 snapshot/collectInteractables 的跨帧遍历一致。
  AS.querySelectorAllCrossFrames = function (selector, scope, frameId) {
    if (!selector) return [];
    const out = [];
    for (const doc of collectFrameDocs(scope, frameId)) {
      try {
        doc.querySelectorAll(selector).forEach((n) => out.push(n));
      } catch (e) { /* 非法 selector 忽略 */ }
    }
    return out;
  };

  // Locate by CSS selector; `index` is 1-based (2nd identical element etc.).
  // 默认跨同源 iframe 查找（与 getContent extract/snapshot 行为一致）。
  AS.locateBySelector = function (selector, index, scope, frameId) {
    if (!selector) return null;
    const nodes = AS.querySelectorAllCrossFrames(selector, scope, frameId);
    const el = index == null || index <= 0 ? nodes[0] : nodes[index - 1];
    return el || null;
  };

  // 可点击元素集合（含常见列表项/选项/开关等）。文本输入框/下拉也纳入，
  // 避免 .search-input / li.suggest-item 等被 isClickable 误判为不可点击。
  const AS_CLICKABLE_SELECTOR = [
    'a[href]',
    'button',
    'input:not([type="hidden"])',
    'textarea',
    'select',
    'label',
    '[onclick]',
    '[role="button"]',
    '[role="link"]',
    '[role="menuitem"]',
    '[role="tab"]',
    '[role="option"]',
    '[role="checkbox"]',
    '[role="radio"]',
    '[role="switch"]',
    'li[class*="item"]',
    'summary',
    '[aria-haspopup]',
    '[tabindex]:not([tabindex="-1"])',
    '[contenteditable="true"]',
  ].join(',');

  // 元素是否可见：非隐藏、非零尺寸、未被覆盖（供文本定位过滤）。跨 iframe 用自身文档坐标系。
  function textNodeVisible(el) {
    if (!el || el.nodeType !== 1) return false;
    const win = ownerWin(el);
    const cs = win.getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') return false;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 && r.height <= 0) return false;
    return true;
  }

  // 元素是否与自身 frame 视口相交（过滤离屏副本，如 left:-9999 或屏外列表项）。
  function isOnScreen(el) {
    if (!el || el.nodeType !== 1) return false;
    const win = ownerWin(el);
    let r;
    try {
      r = el.getBoundingClientRect();
    } catch (e) {
      return false;
    }
    const vw = win.innerWidth || 0;
    const vh = win.innerHeight || 0;
    return r.right > 0 && r.bottom > 0 && r.left < vw && r.top < vh;
  }

  /** 上溯到最近可点击祖先（无则返回原始节点）。 */
  function nearestClickable(el) {
    if (!el) return null;
    if (el.closest) {
      const c = el.closest(AS_CLICKABLE_SELECTOR);
      if (c) return c;
    }
    return el;
  }

  // 对候选列表：过滤不可见 → 上溯可点击 → 去重（保持 DOM 序）。不在此筛“在屏”，
  // 屏下项留给 locate 的 scrollIntoView + mainFramePoint（滚动后再判视口相交）。
  function normalizeCandidates(nodes) {
    const out = [];
    const seen = new Set();
    for (const n of nodes) {
      if (!textNodeVisible(n)) continue;
      const c = nearestClickable(n);
      if (!c || seen.has(c)) continue;
      seen.add(c);
      out.push(c);
    }
    return out;
  }

  // XPath 不支持 ShadowRoot；统一用 DOM 文本遍历，兼容 Document、ShadowRoot 和 iframe 文档。
  function textQueryAll(root, text, mode) {
    const target = String(text).replace(/\s+/g, ' ').trim();
    const out = [];
    try {
      for (const el of root.querySelectorAll('*')) {
        const directText = Array.from(el.childNodes || [])
          .filter((node) => node.nodeType === Node.TEXT_NODE)
          .map((node) => node.nodeValue || '')
          .join(' ')
          .replace(/\s+/g, ' ')
          .trim();
        const value = (el.textContent || '').replace(/\s+/g, ' ').trim();
        if ((mode === 'exact' && directText === target)
          || (mode === 'direct-contains' && directText.includes(target))
          || (mode === 'contains' && value.includes(String(text)))) {
          out.push(el);
        }
      }
    } catch (e) { /* 文档在导航中被替换时返回当前已找到的结果 */ }
    return out;
  }

  function attributeQueryAll(root, attribute, value) {
    const out = [];
    try {
      for (const el of root.querySelectorAll(`[${attribute}]`)) {
        if (el.getAttribute(attribute) === String(value)) out.push(el);
      }
    } catch (e) { /* 文档在导航中被替换时忽略 */ }
    return out;
  }

  // Locate by visible text; `occurrence` is 1-based (Nth element matching the text).
  // `scope` 限定在某区域（如对话框/面板选择器）内查找，避免跨区重复文本（北京/确定等）。
  // 默认跨同源 iframe 查找（按 主文档 → iframe 文档 顺序累计，与 snapshot 遍历一致）。
  AS.locateByText = function (text, occurrence, scope, frameId) {
    if (!text) return null;
    const occ = occurrence == null || occurrence <= 0 ? 1 : occurrence;
    let found = [];
    for (const doc of collectFrameDocs(scope, frameId)) {
      if (found.length >= occ) break;
      const collect = (nodes) => normalizeCandidates(nodes).filter((n) => !found.includes(n));
      // Phase 1: exact normalize-space match
      if (found.length < occ) {
        found = found.concat(collect(textQueryAll(doc, text, 'exact')));
      }
      // Phase 2: contains() on direct text nodes
      if (found.length < occ) {
        found = found.concat(collect(textQueryAll(doc, text, 'direct-contains')));
      }
      // Phase 3: any descendant contains → up-search to nearest clickable ancestor
      if (found.length < occ) {
        found = found.concat(collect(
          textQueryAll(doc, text, 'contains').map(
            (n) => n.closest(AS_CLICKABLE_SELECTOR) || n,
          ),
        ));
      }
      // Phase 4: aria-label / title lookup
      if (found.length < occ) {
        found = found.concat(collect([
          ...attributeQueryAll(doc, 'aria-label', text),
          ...attributeQueryAll(doc, 'title', text),
        ]));
      }
      // Phase 5: glob / fuzzy text matching across clickable elements (scoped)
      if (found.length < occ && (String(text).includes('*') || String(text).includes('?'))) {
        const regex = AS.globToRegExp(text);
        found = found.concat(
          (doc || document)
            .querySelectorAll(AS_CLICKABLE_SELECTOR)
            .filter((n) => {
              const t = (
                n.textContent ||
                n.getAttribute('aria-label') ||
                n.getAttribute('title') ||
                ''
              ).trim();
              return regex.test(t);
            })
            .slice(0, occ - found.length),
        );
      }
    }
    return found.length >= occ ? found[occ - 1] : (found[0] || null);
  };

  AS.locate = function (params) {
    if (params && params.ref != null) return AS.locateByRef(params.ref);
    let el = AS.locateBySelector(
      params.selector,
      params.index,
      params.scope,
      params.frameId,
    );
    if (!el && params.text) {
      el = AS.locateByText(
        params.text,
        params.occurrence,
        params.scope,
        params.frameId,
      );
    }
    return el;
  };

  // --- 索引化可交互快照（getContent mode:snapshot / locateByRef 共用，保证 ref 稳定） ---

  const AS_INTERACTIVE_SELECTOR = [
    'a[href]',
    'button',
    'input:not([type="hidden"])',
    'textarea',
    'select',
    'summary',
    '[contenteditable="true"]',
    '[onclick]',
    '[tabindex]:not([tabindex="-1"])',
    '[role="button"]',
    '[role="link"]',
    '[role="menuitem"]',
    '[role="tab"]',
    '[role="checkbox"]',
    '[role="radio"]',
    '[role="switch"]',
    '[role="combobox"]',
    '[role="textbox"]',
    '[role="searchbox"]',
    '[role="option"]',
    '[aria-haspopup]',
  ].join(',');

  function asIsVisible(el) {
    if (!el) return false;
    if (el.offsetWidth <= 0 && el.offsetHeight <= 0) return false;
    const win = ownerWin(el);
    const cs = win.getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function asRoleOf(el) {
    const role = el.getAttribute('role');
    if (role) return role;
    if (el.tagName === 'A') return 'link';
    if (el.tagName === 'BUTTON') return 'button';
    if (el.tagName === 'INPUT') return el.type || 'textbox';
    if (el.tagName === 'TEXTAREA') return 'textbox';
    if (el.tagName === 'SELECT') return 'combobox';
    if (el.isContentEditable) return 'textbox';
    return el.tagName.toLowerCase();
  }

  function asNameOf(el) {
    const al = el.getAttribute('aria-label');
    if (al && al.trim()) return al.trim();
    const t = el.getAttribute('title');
    if (t && t.trim()) return t.trim();
    const lb = el.getAttribute('aria-labelledby');
    if (lb) {
      const ref = document.getElementById(String(lb).split(/\s+/)[0]);
      if (ref && ref.textContent && ref.textContent.trim()) return ref.textContent.trim().slice(0, 60);
    }
    const ph = el.getAttribute('placeholder');
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
      if (el.value && el.value.trim()) return el.value.trim();
      if (ph && ph.trim()) return ph.trim();
      return '';
    }
    const txt = (el.textContent || '').trim();
    if (txt && txt.length <= 60) return txt;
    const img = el.querySelector('img[alt]');
    if (img && img.getAttribute('alt')) return img.getAttribute('alt');
    const svgTitle = el.querySelector('svg title');
    if (svgTitle && svgTitle.textContent && svgTitle.textContent.trim()) {
      return svgTitle.textContent.trim().slice(0, 60);
    }
    return '';
  }

  function asStateOf(el) {
    const s = [];
    if (el.disabled) s.push('disabled');
    if (el.checked) s.push('checked');
    if (el.selected) s.push('selected');
    const exp = el.getAttribute('aria-expanded');
    if (exp) s.push('expanded:' + exp);
    if (el.getAttribute('aria-haspopup') != null) s.push('haspopup');
    return s.join(' ');
  }

  /**
   * 按 DOM 序收集可见可交互元素（确定性：getContent snapshot 与 locateByRef 共用，
   * 保证 ref 稳定可复现）。可穿透 open shadow DOM；去重嵌套可点击元素；容量上限。
   */
  AS.collectInteractables = function (opts) {
    const max = opts && opts.max ? opts.max : 200;
    const includeShadow = !opts || opts.includeShadow !== false;
    const topOnly = opts && opts.frameId === 0;
    const out = [];
    const seen = new Set();
    const pushEl = (el) => {
      if (!asIsVisible(el)) return;
      for (let p = el.parentElement; p; p = p.parentElement) {
        if (seen.has(p)) return;
      }
      out.push(el);
      seen.add(el);
    };
    const walk = (root) => {
      for (const el of root.querySelectorAll(AS_INTERACTIVE_SELECTOR)) {
        if (out.length >= max) return;
        pushEl(el);
      }
      if (topOnly) return; // 顶层：不穿透 iframe/shadow
      // 穿透 open shadow DOM + 同源 iframe（跨源 contentDocument 访问会抛错，忽略）
      for (const el of root.querySelectorAll('*')) {
        if (out.length >= max) return;
        if (el.shadowRoot) walk(el.shadowRoot);
        if (el.tagName === 'IFRAME') {
          try {
            if (el.contentDocument) walk(el.contentDocument);
          } catch (e) { /* 跨源 iframe 跳过 */ }
        }
      }
    };
    walk(document);
    return out;
  };

  /** 按快照 ref（0 基索引）重新定位同一元素。frameId=0 时与同约束 snapshot 对齐。 */
  AS.locateByRef = function (ref, frameId) {
    if (ref == null) return null;
    const items = AS.collectInteractables({ frameId });
    return items[ref] || null;
  };

  /** 快照项（getContent mode:snapshot 输出）。 */
  AS.snapshotItem = function (el, ref) {
    const item = {
      ref,
      role: asRoleOf(el),
      name: asNameOf(el),
      tag: el.tagName.toLowerCase(),
    };
    if (el.type) item.type = el.type;
    const st = asStateOf(el);
    if (st) item.state = st;
    if (el.tagName === 'A' && el.href) item.href = el.href;
    return item;
  };

  /** 可交互性：可见、未禁用、pointer-events 可用。
   *  SPA 引导层/shading 常盖住元素中心，elementFromPoint 命中检查改为"软提示"：
   *  命中到其它元素时不直接判不可交互（真实点击仍可达），仅打 __asCovered 标记，
   *  交给 background 的 CDP 受信输入回退兜底。 */
  AS.isActionable = function (el) {
    if (!el) return false;
    if (el.disabled) return false;
    if (!asIsVisible(el)) return false;
    const win = ownerWin(el);
    const cs = win.getComputedStyle(el);
    if (cs.pointerEvents === 'none') return false;
    try {
      const doc = el.ownerDocument || document;
      const r = el.getBoundingClientRect();
      const cx = r.left + r.width / 2;
      const cy = r.top + r.height / 2;
      const top = doc.elementFromPoint(cx, cy);
      el.__asCovered = Boolean(top && top !== el && !el.contains(top));
    } catch (e) { /* 忽略覆盖检查异常 */ }
    return true;
  };

  /** 将元素中心换算到主 frame 视口坐标（CDP Input.dispatchMouseEvent 用）。
   *  iframe 内元素沿 window.frameElement 链累加偏移，得到顶层视口 CSS 像素。 */
  AS.mainFramePoint = function (el) {
    if (!el) return null;
    let r;
    try {
      r = el.getBoundingClientRect();
    } catch (e) {
      return null;
    }
    if (r.width <= 0 && r.height <= 0) return null;
    if (!isOnScreen(el)) return null; // 离屏副本/屏外项：不提供坐标，避免盲点
    let x = r.left + r.width / 2;
    let y = r.top + r.height / 2;
    let win = ownerWin(el);
    let guard = 0;
    while (win && win !== window.top && guard++ < 10) {
      const fe = win.frameElement;
      if (!fe) break;
      let fr;
      try {
        fr = fe.getBoundingClientRect();
      } catch (e) {
        break;
      }
      x += fr.left;
      y += fr.top;
      win = fe.ownerDocument && fe.ownerDocument.defaultView;
    }
    const vw = Math.max(window.innerWidth || 1, 1);
    const vh = Math.max(window.innerHeight || 1, 1);
    if (x < 0 || y < 0 || x >= vw || y >= vh) return null;
    return { x: Math.round(x), y: Math.round(y) };
  };

  // 文本候选：跨同源 iframe + shadow 收集（exact/contains/属性兜底），保持 DOM 序。
  function textCandidates(text, scope, cap, frameId) {
    const out = [];
    const seen = new Set();
    const want = cap || 50;
    const docs = collectFrameDocs(scope, frameId);
    for (const doc of docs) {
      if (out.length >= want) break;
      const phases = [
        textQueryAll(doc, text, 'exact'),
        textQueryAll(doc, text, 'direct-contains'),
        textQueryAll(doc, text, 'contains').map((n) => n.closest(AS_CLICKABLE_SELECTOR) || n),
      ];
      for (const nodes of phases) {
        for (const n of normalizeCandidates(nodes)) {
          if (!seen.has(n)) seen.add(n), out.push(n);
          if (out.length >= want) break;
        }
        if (out.length >= want) break;
      }
    }
    if (out.length === 0 && text) {
      for (const doc of docs) {
        for (const n of attributeQueryAll(doc, 'aria-label', text)) if (!seen.has(n)) seen.add(n), out.push(n);
        for (const n of attributeQueryAll(doc, 'title', text)) if (!seen.has(n)) seen.add(n), out.push(n);
      }
    }
    return out;
  }

  // 写动作目标解析：候选按“可见可见性”过滤（含滚动面板内的屏下项，便于 scrollIntoView 后点击）；
  // 歧义时给出最近的容器 class 建议（去重、过滤无意义 token、最多 3 个）。
  function suggestScopes(candidates) {
    const seen = new Set();
    const out = [];
    for (const el of candidates) {
      for (let p = el && el.parentElement, depth = 0; p && depth < 2; p = p.parentElement, depth++) {
        const cls = typeof p.className === 'string' ? p.className.trim() : '';
        for (const token of cls.split(/\s+/)) {
          const t = token.trim();
          if (t.length >= 3 && /^[a-zA-Z_][\w-]*$/.test(t) && !seen.has(t)) {
            seen.add(t);
            out.push('.' + t);
          }
        }
      }
    }
    return out.slice(0, 3);
  }

  // 歧义判定：可见命中 >1 且无 index/occurrence/scope。坐标的“在屏”约束交给 mainFramePoint（滚动后判定）。
  AS.resolveActionTarget = function (params) {
    if (!params) return { ok: false, error: 'not_found' };
    if (params.ref != null) {
      const el = AS.locateByRef(params.ref);
      if (!el) return { ok: false, error: 'not_found' };
      return { ok: true, el, count: 1 };
    }
    const candidates = params.selector
      ? AS.querySelectorAllCrossFrames(params.selector, params.scope, params.frameId)
          .filter((el) => textNodeVisible(el))
      : params.text
        ? textCandidates(params.text, params.scope, 50, params.frameId)
        : [];
    const hasDisambiguator = Boolean(params.scope)
      || (params.index && params.index > 0)
      || (params.text && params.occurrence && params.occurrence > 0);
    if (!hasDisambiguator && candidates.length > 1) {
      return { ok: false, error: 'ambiguous', matches: candidates.length, suggested: suggestScopes(candidates) };
    }
    let idx = 0;
    if (params.selector) idx = params.index && params.index > 0 ? params.index - 1 : 0;
    else if (params.text) idx = (params.occurrence && params.occurrence > 0 ? params.occurrence - 1 : (params.index && params.index > 0 ? params.index - 1 : 0));
    const el = candidates[idx] || null;
    if (!el) return { ok: false, error: 'not_found' };
    return { ok: true, el, count: candidates.length };
  };

  /** 轮询等待元素出现且可交互（用于 click/type/hover/wait）。 */
  AS.waitForElement = function (locator, timeout, interval) {
    const t = timeout == null ? 3000 : timeout;
    const iv = interval == null ? 150 : interval;
    return new Promise((resolve) => {
      const start = Date.now();
      const tick = () => {
        let el = AS.locate(locator);
        if (!el && locator && locator.ref != null) el = AS.locateByRef(locator.ref);
        if (el && AS.isActionable(el)) return resolve(el);
        if (Date.now() - start >= t) return resolve(el || null);
        setTimeout(tick, iv);
      };
      tick();
    });
  };
})();

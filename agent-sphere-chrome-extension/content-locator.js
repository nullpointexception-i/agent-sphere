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

  AS.xpathQuery = function (expr, doc) {
    const root = doc || document;
    try {
      return root.evaluate(
        expr,
        root,
        null,
        XPathResult.FIRST_ORDERED_NODE_TYPE,
        null,
      ).singleNodeValue;
    } catch (e) {
      return null;
    }
  };

  // All matches for an XPath expression (document order).
  AS.xpathQueryAll = function (expr, doc) {
    const root = doc || document;
    try {
      const res = root.evaluate(expr, root, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
      const out = [];
      for (let i = 0; i < res.snapshotLength; i++) out.push(res.snapshotItem(i));
      return out;
    } catch (e) {
      return [];
    }
  };

  // Locate by CSS selector; `index` is 1-based (2nd identical element etc.).
  AS.locateBySelector = function (selector, index) {
    if (!selector) return null;
    try {
      const nodes = document.querySelectorAll(selector);
      const el = index == null || index <= 0 ? nodes[0] : nodes[index - 1];
      return el || null;
    } catch (e) {
      return null;
    }
  };

  // Locate by visible text; `occurrence` is 1-based (Nth element matching the text).
  AS.locateByText = function (text, occurrence) {
    if (!text) return null;
    const occ = occurrence == null || occurrence <= 0 ? 1 : occurrence;
    const safe = String(text).replace(/'/g, "\\'");
    let found = [];

    // Phase 1: exact normalize-space match
    found = AS.xpathQueryAll(`//*[text()[normalize-space()='${safe}']]`);
    // Phase 2: contains() on direct text nodes
    if (found.length < occ) found = AS.xpathQueryAll(`//*[contains(text(), '${safe}')]`);
    // Phase 3: any descendant contains → up-search to nearest clickable ancestor
    if (found.length < occ) {
      found = [...new Set(
        AS.xpathQueryAll(`//*[contains(., '${safe}')]`).map((n) => n.closest(
          'a, button, [role="button"], [onclick], summary, [aria-haspopup], [tabindex]:not([tabindex="-1"])',
        ) || n),
      )];
    }
    // Phase 4: aria-label / title lookup
    if (found.length < occ) {
      const byLabel = AS.locateBySelector(`[aria-label="${text}"]`);
      const byTitle = AS.locateBySelector(`[title="${text}"]`);
      found = [byLabel, byTitle].filter(Boolean);
    }
    // Phase 5: glob / fuzzy text matching across clickable elements
    if (found.length < occ && (String(text).includes('*') || String(text).includes('?'))) {
      const regex = AS.globToRegExp(text);
      found = [...document.querySelectorAll(
        'a, button, [role="button"], [onclick], summary, [aria-haspopup]',
      )].filter((n) => {
        const t = (
          n.textContent ||
          n.getAttribute('aria-label') ||
          n.getAttribute('title') ||
          ''
        ).trim();
        return regex.test(t);
      });
    }
    return found.length >= occ ? found[occ - 1] : (found[0] || null);
  };

  AS.locate = function (params) {
    if (params && params.ref != null) return AS.locateByRef(params.ref);
    let el = AS.locateBySelector(params.selector, params.index);
    if (!el && params.text) el = AS.locateByText(params.text, params.occurrence);
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
    const cs = getComputedStyle(el);
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

  /** 按快照 ref（0 基索引）重新定位同一元素。 */
  AS.locateByRef = function (ref) {
    if (ref == null) return null;
    const items = AS.collectInteractables({});
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

  /** 可交互性：可见、未禁用、pointer-events 可用、未被其它元素覆盖。 */
  AS.isActionable = function (el) {
    if (!el) return false;
    if (el.disabled) return false;
    if (!asIsVisible(el)) return false;
    const cs = getComputedStyle(el);
    if (cs.pointerEvents === 'none') return false;
    try {
      const r = el.getBoundingClientRect();
      const cx = r.left + r.width / 2;
      const cy = r.top + r.height / 2;
      const top = document.elementFromPoint(cx, cy);
      if (top && top !== el && !el.contains(top)) return false;
    } catch (e) { /* 忽略覆盖检查异常 */ }
    return true;
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

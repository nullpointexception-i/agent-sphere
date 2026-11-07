/**
 * Content-script locator helpers (ISOLATED world).
 * Loaded before content.js; attaches to the shared window.__asContent namespace.
 *
 * Borrows Ui.Vision's dom_utils ideas: text glob matching (with `*`/`?`
 * wildcards), XPath queries, and a final aria-label lookup.
 */
window.__asContent = window.__asContent || {};

(function () {
  // Idempotency guard: askContent deletes the injection cache and re-injects on "Receiving end does not exist"
  // (tab-manager.js); without the guard every AS.* in this file would be defined twice.
  //
  // Must live inside the IIFE: this file is loaded via chrome.scripting.executeScript({files}) as a **classic script**,
  // and a top-level return is a SyntaxError in classic scripts - placed outside the IIFE the whole file would not run,
  // window.__asContent would never be created, and every AS.* call in content.js would throw TypeError.
  // The equivalent guard in content.js also sits inside its IIFE (see the top of that file).
  if (window.__asLocatorLoaded) return;
  window.__asLocatorLoaded = true;

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

  // The window the element lives in (use its own document's window inside a same-origin iframe so coordinates/styles match).
  function ownerWin(el) {
    return el && el.ownerDocument && el.ownerDocument.defaultView
      ? el.ownerDocument.defaultView
      : window;
  }

  // Document collection across same-origin iframes + open shadow DOM (order: main document -> iframes/contentDocument encountered).
  // frameId=0 -> top-level document only (overlays are usually rendered in a top-level dialog; skipping iframes reduces noise/ambiguity).
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
          } catch (e) { /* cross-origin iframe, skip */ }
        }
      }
    };
    const walk = (root) => {
      pick(root);
      if (topOnly) return; // top level takes only the document body; do not descend into iframe/shadow
      for (const el of root.querySelectorAll('*')) {
        if (el.shadowRoot) walk(el.shadowRoot);
        if (el.tagName === 'IFRAME') {
          try {
            if (el.contentDocument) walk(el.contentDocument);
          } catch (e) { /* cross-origin iframe, skip */ }
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
      // Top level + scope: include it only when the top level contains the scope container (no iframe/shadow penetration)
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
    // scope may live in a same-origin iframe or a ShadowRoot, so querying the top-level document alone is not enough.
    for (const root of reachable) {
      try {
        if (root.matches && root.matches(scope)) walk(root);
        root.querySelectorAll(scope).forEach((match) => walk(match));
      } catch (e) { /* ignore an invalid scope or a document that is navigating */ }
    }
    return docs;
  }

  AS.collectFrameDocs = collectFrameDocs;

  // Collect elements by selector per document (main document + same-origin iframes + shadow), DOM order stable,
  // consistent with the cross-frame traversal in snapshot/collectInteractables.
  AS.querySelectorAllCrossFrames = function (selector, scope, frameId) {
    if (!selector) return [];
    const out = [];
    for (const doc of collectFrameDocs(scope, frameId)) {
      try {
        doc.querySelectorAll(selector).forEach((n) => out.push(n));
      } catch (e) { /* ignore an invalid selector */ }
    }
    return out;
  };

  // Locate by CSS selector; `index` is 1-based (2nd identical element etc.).
  // Search across same-origin iframes by default (consistent with getContent extract/snapshot behavior).
  AS.locateBySelector = function (selector, index, scope, frameId) {
    if (!selector) return null;
    const nodes = AS.querySelectorAllCrossFrames(selector, scope, frameId);
    const el = index == null || index <= 0 ? nodes[0] : nodes[index - 1];
    return el || null;
  };

  // Clickable element set (including common list items/options/switches). Text inputs/selects are included too,
  // to keep .search-input / li.suggest-item and the like from being misjudged as not clickable by isClickable.
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

  // Whether an element is visible: not hidden, non-zero size, not covered (used to filter text matches). Cross-iframe uses the element's own document coordinate system.
  function textNodeVisible(el) {
    if (!el || el.nodeType !== 1) return false;
    const win = ownerWin(el);
    const cs = win.getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') return false;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 && r.height <= 0) return false;
    return true;
  }

  // Whether the element intersects its own frame viewport (filters off-screen copies such as left:-9999 or list items outside the viewport).
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

  /** Walk up to the nearest clickable ancestor (returns the original node if none). */
  function nearestClickable(el) {
    if (!el) return null;
    if (el.closest) {
      const c = el.closest(AS_CLICKABLE_SELECTOR);
      if (c) return c;
    }
    return el;
  }

  // For a candidate list: filter invisible -> walk up to clickable -> dedupe (preserving DOM order). On-screen filtering is not done here;
  // off-screen items are left to locate's scrollIntoView + mainFramePoint (viewport intersection is checked after scrolling).
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

  // XPath does not support ShadowRoot; use DOM text traversal instead, compatible with Document, ShadowRoot and iframe documents.
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
    } catch (e) { /* return the results found so far when the document is replaced during navigation */ }
    return out;
  }

  function attributeQueryAll(root, attribute, value) {
    const out = [];
    try {
      for (const el of root.querySelectorAll(`[${attribute}]`)) {
        if (el.getAttribute(attribute) === String(value)) out.push(el);
      }
    } catch (e) { /* ignore when the document is replaced during navigation */ }
    return out;
  }

  // Locate by visible text; `occurrence` is 1-based (Nth element matching the text).
  // `scope` restricts the search to an area (such as a dialog/panel selector) to avoid duplicate text across areas (Beijing/Confirm etc.).
  // Searches across same-origin iframes by default (accumulated in main document -> iframe document order, consistent with snapshot traversal).
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

  // --- Indexed interactive snapshot (shared by getContent mode:snapshot / locateByRef for stable refs) ---

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
   * Collect visible interactive elements in DOM order (deterministic: shared by getContent snapshot and locateByRef,
   * so refs are stable and reproducible). Penetrates open shadow DOM; dedupes nested clickable elements; has a capacity cap.
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
      if (topOnly) return; // top level: do not penetrate iframe/shadow
      // Penetrate open shadow DOM + same-origin iframes (accessing a cross-origin contentDocument throws, so ignore it)
      for (const el of root.querySelectorAll('*')) {
        if (out.length >= max) return;
        if (el.shadowRoot) walk(el.shadowRoot);
        if (el.tagName === 'IFRAME') {
          try {
            if (el.contentDocument) walk(el.contentDocument);
          } catch (e) { /* cross-origin iframe, skip */ }
        }
      }
    };
    walk(document);
    return out;
  };

  /** Re-locate the same element by snapshot ref (0-based index). With frameId=0 it aligns with a snapshot taken under the same constraints. */
  AS.locateByRef = function (ref, frameId) {
    if (ref == null) return null;
    const items = AS.collectInteractables({ frameId });
    return items[ref] || null;
  };

  /** Snapshot item (output of getContent mode:snapshot). */
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
    const bounds = AS.mainFrameBounds(el);
    if (bounds) item.bounds = bounds;
    return item;
  };

  /** Interactability: visible, not disabled, pointer-events available.
   *  SPA overlays/shading often cover the element center, so the elementFromPoint hit check is downgraded to a "soft hint":
   *  hitting another element does not immediately mark it non-interactive (a real click may still reach it); it only sets __asCovered,
   *  leaving the CDP trusted-input fallback in background to handle it. */
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
    } catch (e) { /* ignore coverage-check errors */ }
    return true;
  };

  /** Pre-assertion: split "can it be operated" into a reportable state, so locate can check it once before and after scrolling.
   *  Corresponds to Codex's elementState visible/enabled - it runs twice around scrollIntoView,
   *  while ours reports only a clickable boolean in the result without blocking, so disabled buttons were clicked too.
   *  Note that covered is deliberately excluded here: isActionable's "soft hint" philosophy stays as is,
   *  and occlusion is left to writeAction's fallback retries rather than being declared fatal here. */
  AS.assertActionable = function (el) {
    if (!el || el.nodeType !== 1) return { ok: false, state: 'connected' };
    if (!el.isConnected) return { ok: false, state: 'connected' };
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return { ok: false, state: 'enabled' };
    if (!asIsVisible(el)) return { ok: false, state: 'visible' };
    try {
      if (ownerWin(el).getComputedStyle(el).pointerEvents === 'none') return { ok: false, state: 'visible' };
    } catch (e) { /* style-read errors do not block */ }
    return { ok: true, state: null };
  };

  /** Wait for element geometry to settle: the rect must be identical for stableFrames consecutive frames.
   *  Replaces the former blind `setTimeout(60)` - that constant assumed the scroll animation had finished, so with lazy-loaded
   *  layout expansion / virtual list reflow / SPA expansion it inevitably captured intermediate coordinates.
   *  Parameters copied from Codex waitForStableBoundingRect: settled after 2 consecutive identical rects, at most 10 frames.
   *  A setTimeout(50) fallback prevents an endless wait when rAF is throttled in a background tab. */
  AS.waitStableRect = function (el, opts) {
    const stableFrames = (opts && opts.stableFrames) || 2;
    const maxFrames = (opts && opts.maxFrames) || 10;
    const win = el && el.ownerDocument && el.ownerDocument.defaultView;
    if (!win || typeof win.requestAnimationFrame !== 'function') return Promise.resolve(null);
    const waitFrame = () => new Promise((resolve) => {
      let done = false;
      const timer = win.setTimeout(() => { if (!done) { done = true; resolve(); } }, 50);
      win.requestAnimationFrame(() => {
        if (done) return;
        done = true;
        win.clearTimeout(timer);
        resolve();
      });
    });
    const sameRect = (a, b) => a.left === b.left && a.top === b.top
      && a.width === b.width && a.height === b.height;
    return (async () => {
      let prev;
      try { prev = el.getBoundingClientRect(); } catch (e) { return null; }
      let stable = 0;
      for (let i = 0; i < maxFrames; i++) {
        await waitFrame();
        let cur;
        try { cur = el.getBoundingClientRect(); } catch (e) { return null; }
        if (sameRect(prev, cur)) {
          if (++stable >= stableFrames) return cur;
        } else {
          stable = 0;
          prev = cur;
        }
      }
      return null; // did not settle -> the caller treats it as not clickable, never click blindly
    })();
  };

  /** label follows redirection: resolve <label> to the real control it is associated with.
   *  Corresponds to Codex retarget(el,"follow-label") - it does this as the first step of fill/selectText/selectOptions
   *  and similar operations. Login/search pages have many controls structured as a label wrapping an input,
   *  and the ref the LLM gives often lands on the label. */
  AS.retargetByLabel = function (el) {
    if (!el || el.nodeType !== 1 || el.tagName !== 'LABEL') return el;
    const doc = el.ownerDocument;
    let ctrl = null;
    try { ctrl = el.control || null; } catch (e) { /* control unavailable in old browsers */ }
    if (!ctrl && el.htmlFor) {
      try { ctrl = doc.getElementById(el.htmlFor); } catch (e) { /* ignore */ }
    }
    if (!ctrl) {
      const t = (el.textContent || '').trim();
      if (t) {
        const pool = doc.querySelectorAll('input,select,textarea,button,[contenteditable]');
        for (const c of pool) {
          let labels = null;
          try { labels = c.labels; } catch (e) { /* ignore */ }
          if (labels && Array.prototype.some.call(labels, (l) => (l.textContent || '').trim() === t)) {
            ctrl = c;
            break;
          }
        }
      }
    }
    return ctrl && ctrl.nodeType === 1 ? ctrl : el;
  };

  /** Hit test at main-viewport coordinates: confirm the click point really lands on the target, not covered by something else.
   *  elementFromPoint uses this frame's viewport coordinates while mainFramePoint returns main-viewport coordinates,
   *  so the frame offset is subtracted. A cross-origin iframe cannot be read -> return ok:true (no blocking),
   *  leaving it to the CDP DOM.getNodeForLocation fallback on the background side.
   *  desc looks like <tag#id.class>, the same format as Codex describeBackendNodeForHitTarget. */
  AS.hitDescriptorAt = function (el, mainX, mainY) {
    try {
      const doc = el.ownerDocument;
      if (!doc) return { ok: true, desc: '' };
      const off = frameOffsetOf(el);
      const hit = doc.elementFromPoint(mainX - off.x, mainY - off.y);
      if (!hit) return { ok: false, desc: '<none>' };
      if (hit === el || el.contains(hit) || hit.contains(el)) return { ok: true, desc: '' };
      return { ok: false, desc: describeLite(hit) };
    } catch (e) {
      return { ok: true, desc: '' }; // cross-origin / document navigating -> do not block
    }
  };

  /** Cumulative offset of the element relative to the top-level viewport (accumulated along the frameElement chain inside iframes). */
  function frameOffsetOf(el) {
    let x = 0;
    let y = 0;
    let win = ownerWin(el);
    let guard = 0;
    while (win && win !== window.top && guard++ < 10) {
      const fe = win.frameElement;
      if (!fe) break;
      let fr;
      try { fr = fe.getBoundingClientRect(); } catch (e) { break; }
      x += fr.left;
      y += fr.top;
      win = fe.ownerDocument && fe.ownerDocument.defaultView;
    }
    return { x, y };
  }

  /** Lightweight element description <tag#id.class>, used to name the interceptor in failure diagnostics. */
  function describeLite(node) {
    if (!node || node.nodeType !== 1) return '<unknown>';
    const tag = (node.tagName || 'unknown').toLowerCase();
    let id = '';
    let cls = '';
    try {
      id = node.id || '';
      cls = (node.getAttribute('class') || '').trim();
    } catch (e) { /* ignore */ }
    const clsOut = cls ? '.' + cls.split(/\s+/).slice(0, 3).join('.') : '';
    return `<${tag}${id ? '#' + id : ''}${clsOut}>`;
  }

  /** Convert the element center to main-frame viewport coordinates (for CDP Input.dispatchMouseEvent).
   *  For elements inside an iframe, accumulate offsets along the window.frameElement chain to get top-level viewport CSS pixels. */
  AS.mainFramePoint = function (el) {
    if (!el) return null;
    let r;
    try {
      r = el.getBoundingClientRect();
    } catch (e) {
      return null;
    }
    if (r.width <= 0 && r.height <= 0) return null;
    if (!isOnScreen(el)) return null; // off-screen copy/off-viewport item: provide no coordinates to avoid blind clicks
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

  /** Bounding box of the element in the top-level viewport (CSS px), for aligning click coordinates. Inside iframes, accumulate offsets along the frameElement chain. */
  AS.mainFrameBounds = function (el) {
    if (!el) return null;
    let r;
    try {
      r = el.getBoundingClientRect();
    } catch (e) {
      return null;
    }
    if (r.width <= 0 && r.height <= 0) return null;
    let left = r.left;
    let top = r.top;
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
      left += fr.left;
      top += fr.top;
      win = fe.ownerDocument && fe.ownerDocument.defaultView;
    }
    return { x: Math.round(left), y: Math.round(top), width: Math.round(r.width), height: Math.round(r.height) };
  };

  /**
   * clickAt coordinate resolution: device pixels (screenshot) -> CSS px (divide by dpr) -> elementFromPoint to find the nearest interactive ancestor.
   * Returns {ok, x, y, interactive, tag, text}; interactive=false means a non-interactive area was hit (the caller clicks raw with a warning).
   */
  AS.locatePoint = function (x, y, frameId) {
    const dpr = (typeof window !== 'undefined' && window.devicePixelRatio) || 1;
    const cssX = x / dpr;
    const cssY = y / dpr;
    const doc = document;
    let top = null;
    try {
      top = doc.elementFromPoint(cssX, cssY);
    } catch (e) {
      return { ok: false, error: 'elementFromPoint failed' };
    }
    if (!top) return { ok: true, x: cssX, y: cssY, interactive: false };
    let el = top;
    let guard = 0;
    while (el && el.nodeType === 1 && guard++ < 20) {
      if (el.matches && el.matches(AS_INTERACTIVE_SELECTOR)) break;
      el = el.parentElement;
    }
    if (!el || el === document.documentElement) {
      return { ok: true, x: cssX, y: cssY, interactive: false };
    }
    const point = AS.mainFramePoint(el);
    if (!point) {
      return { ok: true, x: cssX, y: cssY, interactive: false };
    }
    return {
      ok: true,
      x: point.x,
      y: point.y,
      interactive: true,
      tag: el.tagName.toLowerCase(),
      text: (el.innerText || el.textContent || '').trim().slice(0, 60),
      ref: el.__asRef != null ? el.__asRef : undefined,
    };
  };

  // Text candidates: collected across same-origin iframes + shadow (exact/contains/attribute fallback), preserving DOM order.
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

  // Write action target resolution: candidates are filtered by visibility (including off-screen items inside scrollable panels, for clicking after scrollIntoView);
  // on ambiguity, suggest the nearest container class (deduped, meaningless tokens filtered, at most 3).
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

  // Ambiguity rule: more than one visible hit and no index/occurrence/scope. The coordinate "on screen" constraint is left to mainFramePoint (decided after scrolling).
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

  /** Poll until the element appears and is interactive (used by click/type/hover/wait). */
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

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
    let el = AS.locateBySelector(params.selector, params.index);
    if (!el && params.text) el = AS.locateByText(params.text, params.occurrence);
    return el;
  };
})();

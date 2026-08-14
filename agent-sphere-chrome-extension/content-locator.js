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

  AS.locateBySelector = function (selector) {
    if (!selector) return null;
    try {
      return document.querySelector(selector);
    } catch (e) {
      return null;
    }
  };

  AS.locateByText = function (text) {
    if (!text) return null;
    const safe = String(text).replace(/'/g, "\\'");
    // Phase 1: exact normalize-space match
    let el = AS.xpathQuery(`//*[text()[normalize-space()='${safe}']]`);
    // Phase 2: contains() on direct text nodes
    if (!el) el = AS.xpathQuery(`//*[contains(text(), '${safe}')]`);
    // Phase 3: any descendant contains → up-search to nearest clickable ancestor
    if (!el) {
      const anyNode = AS.xpathQuery(`//*[contains(., '${safe}')]`);
      if (anyNode) {
        el = anyNode.closest(
          'a, button, [role="button"], [onclick], summary, [aria-haspopup], [tabindex]:not([tabindex="-1"])',
        );
        if (!el) el = anyNode; // React delegation handles clicks on children
      }
    }
    // aria-label / title lookup
    if (!el) el = AS.locateBySelector(`[aria-label="${text}"]`);
    if (!el) el = AS.locateBySelector(`[title="${text}"]`);
    // Glob / fuzzy text matching across clickable elements
    if (!el && (String(text).includes('*') || String(text).includes('?'))) {
      const regex = AS.globToRegExp(text);
      const candidates = [
        ...document.querySelectorAll(
          'a, button, [role="button"], [onclick], summary, [aria-haspopup]',
        ),
      ];
      el = candidates.find((n) => {
        const t = (
          n.textContent ||
          n.getAttribute('aria-label') ||
          n.getAttribute('title') ||
          ''
        ).trim();
        return regex.test(t);
      });
      el = el || null;
    }
    return el;
  };

  AS.locate = function (params) {
    let el = AS.locateBySelector(params.selector);
    if (!el && params.text) el = AS.locateByText(params.text);
    return el;
  };
})();

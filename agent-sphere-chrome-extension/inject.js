/**
 * MAIN-world eval bridge (web_accessible).
 *
 * Content scripts run in an ISOLATED world and cannot read the page's JS
 * globals. This script is injected into the page's MAIN world via a
 * <script src=chrome-extension://.../inject.js> element and executes code with
 * a page-world `eval`. Results are cloned back over postMessage.
 *
 * CSP note: pages whose CSP forbids `unsafe-eval` will block the eval inside
 * and the bridge reports the error back — the caller then escalates to the
 * scripting/MAIN or chrome.debugger tiers.
 */
(function () {
  if (window.__asInjectInstalled) return;
  window.__asInjectInstalled = true;

  function serializeValue(v) {
    if (v === undefined) return '__NO_RETURN__';
    try {
      return JSON.parse(JSON.stringify(v));
    } catch (e) {
      return v == null ? null : String(v);
    }
  }

  window.addEventListener('message', (event) => {
    if (event.source !== window) return;
    const data = event.data;
    if (!data || data.type !== 'agent-sphere:inject-eval') return;

    const { requestId, code } = data;
    let ok = true;
    let result;
    try {
      const run = new Function('return (async () => { ' + code + '\n })()');
      result = run();
      // Resolve async results; fall back to sync if the value is not a promise.
      if (result && typeof result.then === 'function') {
        result.then(
          (v) => window.postMessage({ type: 'agent-sphere:inject-eval-response', requestId, ok: true, result: serializeValue(v) }, '*'),
          (err) => window.postMessage({ type: 'agent-sphere:inject-eval-response', requestId, ok: false, result: err && err.message ? err.message : String(err) }, '*'),
        );
        return;
      }
      result = serializeValue(result);
    } catch (err) {
      ok = false;
      result = err && err.message ? err.message : String(err);
    }
    window.postMessage({ type: 'agent-sphere:inject-eval-response', requestId, ok, result }, '*');
  });

  window.postMessage({ type: 'agent-sphere:inject-ready' }, '*');
})();

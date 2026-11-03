(function () {
  // Idempotency guard: content.js may re-inject this MAIN-world script into the same
  // page (multiple injection triggers / sub-frame sweeps). A top-level `const`
  // redeclaration would throw, and re-wrapping Storage.prototype would stack wrappers —
  // so a re-install is a no-op.
  if (window.__asPageScriptInstalled) return;
  window.__asPageScriptInstalled = true;

  window.__opencode_lastWindowOpen = 0;
  window.__opencode_origOpen = window.open;
  window.open = function () {
    window.__opencode_lastWindowOpen = Date.now();
    return window.__opencode_origOpen.apply(this, arguments);
  };

  // --- MAIN-world bridge: forward widget auth/session state to the content script ---
  // (content scripts run in an ISOLATED world and cannot read the page's storage)
  // token 现在存在 localStorage（同一浏览器所有标签页共用一份），sessionId 仍按标签页区分。
  function forwardWidgetSession() {
    let sessionId = null;
    let user = null;
    try {
      sessionId = sessionStorage.getItem('agent-sphere-widget:active-session');
      user = localStorage.getItem('agent-sphere-widget:agent-user');
      // 迁移期兜底：旧版本写在 sessionStorage
      if (!user) user = sessionStorage.getItem('agent-sphere-widget:agent-user');
    } catch (e) { /* storage blocked */ }
    window.postMessage({ type: 'agent-sphere:session-data', sessionId, user }, '*');
  }

  // Widget dispatches this custom event on every session selection (CopilotView.tsx)
  window.addEventListener('agent-sphere:session-change', forwardWidgetSession);

  // Catch any direct storage write for the widget keys (robustness; setItem + removeItem).
  // user 在 localStorage、active-session 在 sessionStorage —— 两个都要拦。
  const __asOrigSetItem = Storage.prototype.setItem;
  const __asOrigRemoveItem = Storage.prototype.removeItem;
  const __asIsWidgetStorage = (target) => target === window.localStorage || target === window.sessionStorage;
  Storage.prototype.setItem = function (key, value) {
    try {
      if (__asIsWidgetStorage(this) && typeof key === 'string' && key.indexOf('agent-sphere-widget:') === 0) {
        setTimeout(forwardWidgetSession, 0);
      }
    } catch (e) { /* ignore */ }
    return __asOrigSetItem.apply(this, arguments);
  };
  Storage.prototype.removeItem = function (key) {
    try {
      if (__asIsWidgetStorage(this) && typeof key === 'string' && key.indexOf('agent-sphere-widget:') === 0) {
        setTimeout(forwardWidgetSession, 0);
      }
    } catch (e) { /* ignore */ }
    return __asOrigRemoveItem.apply(this, arguments);
  };

  // Initial state: page-script.js may load after the widget already wrote storage
  setTimeout(forwardWidgetSession, 300);
  setTimeout(forwardWidgetSession, 1500);
})();

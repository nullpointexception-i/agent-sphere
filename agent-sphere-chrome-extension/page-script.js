window.__opencode_lastWindowOpen = 0;
window.__opencode_origOpen = window.open;
window.open = function() {
  window.__opencode_lastWindowOpen = Date.now();
  return window.__opencode_origOpen.apply(this, arguments);
};

// --- MAIN-world bridge: forward widget auth/session state to the content script ---
// (content scripts run in an ISOLATED world and cannot read the page's sessionStorage)
function forwardWidgetSession() {
  let sessionId = null;
  let user = null;
  try {
    sessionId = sessionStorage.getItem('agent-sphere-widget:active-session');
    user = sessionStorage.getItem('agent-sphere-widget:agent-user');
  } catch (e) { /* storage blocked */ }
  window.postMessage({ type: 'agent-sphere:session-data', sessionId, user }, '*');
}

// Widget dispatches this custom event on every session selection (CopilotView.tsx)
window.addEventListener('agent-sphere:session-change', forwardWidgetSession);

// Catch any direct storage write for the widget keys (robustness; setItem + removeItem)
const __asOrigSetItem = Storage.prototype.setItem;
const __asOrigRemoveItem = Storage.prototype.removeItem;
Storage.prototype.setItem = function(key, value) {
  try {
    if (this === window.sessionStorage && typeof key === 'string' && key.indexOf('agent-sphere-widget:') === 0) {
      setTimeout(forwardWidgetSession, 0);
    }
  } catch (e) { /* ignore */ }
  return __asOrigSetItem.apply(this, arguments);
};
Storage.prototype.removeItem = function(key) {
  try {
    if (this === window.sessionStorage && typeof key === 'string' && key.indexOf('agent-sphere-widget:') === 0) {
      setTimeout(forwardWidgetSession, 0);
    }
  } catch (e) { /* ignore */ }
  return __asOrigRemoveItem.apply(this, arguments);
};

// Initial state: page-script.js may load after the widget already wrote storage
setTimeout(forwardWidgetSession, 300);
setTimeout(forwardWidgetSession, 1500);

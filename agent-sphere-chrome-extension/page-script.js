window.__opencode_lastWindowOpen = 0;
window.__opencode_origOpen = window.open;
window.open = function() {
  window.__opencode_lastWindowOpen = Date.now();
  return window.__opencode_origOpen.apply(this, arguments);
};

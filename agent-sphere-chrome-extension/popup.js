(function () {
  const DEFAULT_SETTINGS = { frontendUrl: 'http://localhost:8000', backendUrl: 'http://localhost:8080' };

  // --- Tab switching ---
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
      tab.classList.add('active');
      document.getElementById('panel' + tab.dataset.tab.charAt(0).toUpperCase() + tab.dataset.tab.slice(1)).classList.add('active');
    });
  });

  // --- Save settings ---
  document.getElementById('btnSave').addEventListener('click', () => {
    const settings = {
      frontendUrl: document.getElementById('inputFrontend').value.trim() || DEFAULT_SETTINGS.frontendUrl,
      backendUrl: document.getElementById('inputBackend').value.trim() || DEFAULT_SETTINGS.backendUrl,
    };
    chrome.storage.local.set({ settings }).catch(() => {});
    const btn = document.getElementById('btnSave');
    btn.textContent = '✓ Saved';
    btn.className = 'btn btn-saved';
    setTimeout(() => { btn.textContent = 'Save Settings'; btn.className = 'btn btn-primary'; }, 1500);
  });

  // --- Clear logs ---
  document.getElementById('btnClearLogs').addEventListener('click', () => {
    chrome.storage.local.set({ logs: [] }).catch(() => {});
  });

  // --- Render ---
  function render(data) {
    const connected = data.connected;
    const settings = data.settings || DEFAULT_SETTINGS;

    // Status badge
    const badge = document.getElementById('statusBadge');
    badge.className = 'status-badge ' + (connected ? 'on' : 'off');
    document.getElementById('statusText').textContent = connected ? 'On' : 'Off';

    // Info panel
    document.getElementById('infoUser').textContent = data.displayName || (data.token ? 'Logged in' : '-');
    document.getElementById('infoSession').textContent = data.sessionId ? '#' + data.sessionId : '-';
    document.getElementById('infoBackend').textContent = settings.backendUrl || data.baseUrl || '-';
    document.getElementById('infoFrontend').textContent = settings.frontendUrl || '-';

    // Settings inputs (only set value if not focused)
    const fe = document.getElementById('inputFrontend');
    const be = document.getElementById('inputBackend');
    if (fe !== document.activeElement) fe.value = settings.frontendUrl || '';
    if (be !== document.activeElement) be.value = settings.backendUrl || '';

    // Logs
    const logs = data.logs || [];
    const container = document.getElementById('logContainer');
    if (logs.length === 0) {
      container.innerHTML = '<div class="empty">No operations yet</div>';
    } else {
      container.innerHTML = logs.slice(-30).reverse().map(log => {
        const time = log.time ? new Date(log.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '';
        return `<div class="log-row"><span class="log-time">${time}</span><span class="log-icon">${log.success ? '✅' : '❌'}</span><span class="log-action">${log.action}</span><span class="log-detail">${log.detail || ''}</span></div>`;
      }).join('');
    }
  }

  // Initial load
  chrome.storage.local.get(['token', 'sessionId', 'displayName', 'baseUrl', 'connected', 'settings', 'logs'], render);

  // Listen for changes
  chrome.storage.onChanged.addListener(() => {
    chrome.storage.local.get(['token', 'sessionId', 'displayName', 'baseUrl', 'connected', 'settings', 'logs'], render);
  });
})();

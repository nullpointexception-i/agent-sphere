(function () {
  const DEFAULT_SETTINGS = { frontendUrls: ['http://bole.buukle.top'], mainUrl: 'http://as.buukle.top', backendUrl: 'http://as.buukle.top' };

  function getFrontendUrls(settings) {
    if (settings && Array.isArray(settings.frontendUrls)) {
      return settings.frontendUrls.filter(Boolean);
    }
    if (settings && settings.frontendUrl) {
      return [settings.frontendUrl];
    }
    return [];
  }

  // --- Tab switching ---
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
      tab.classList.add('active');
      document.getElementById('panel' + tab.dataset.tab.charAt(0).toUpperCase() + tab.dataset.tab.slice(1)).classList.add('active');
    });
  });

  // --- Frontend URL rows (动态多行) ---
  function createFrontendRow(value) {
    const row = document.createElement('div');
    row.className = 'frontend-row';
    const input = document.createElement('input');
    input.type = 'url';
    input.className = 'input-frontend';
    input.placeholder = 'http://bole.buukle.top';
    input.value = value || '';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn-remove';
    btn.textContent = '✕';
    btn.addEventListener('click', () => row.remove());
    row.appendChild(input);
    row.appendChild(btn);
    return row;
  }

  function renderFrontendRows(urls) {
    const list = document.getElementById('frontendUrlList');
    list.innerHTML = '';
    for (const u of urls) list.appendChild(createFrontendRow(u));
  }

  document.getElementById('btnAddFrontend').addEventListener('click', () => {
    document.getElementById('frontendUrlList').appendChild(createFrontendRow(''));
  });

  // --- Save settings ---
  document.getElementById('btnSave').addEventListener('click', () => {
    const urls = Array.from(document.querySelectorAll('.input-frontend'))
      .map(i => i.value.trim())
      .filter(Boolean);
    const settings = {
      frontendUrls: urls.length ? urls : DEFAULT_SETTINGS.frontendUrls,
      mainUrl: document.getElementById('inputMain').value.trim() || DEFAULT_SETTINGS.mainUrl,
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
    const frontendUrls = getFrontendUrls(settings);

    // Status badge
    const badge = document.getElementById('statusBadge');
    badge.className = 'status-badge ' + (connected ? 'on' : 'off');
    document.getElementById('statusText').textContent = connected ? 'On' : 'Off';

    // Info panel
    document.getElementById('infoUser').textContent = data.displayName || (data.token ? 'Logged in' : '-');
    document.getElementById('infoSession').textContent = data.sessionId ? '#' + data.sessionId : '-';
    document.getElementById('infoBackend').textContent = settings.backendUrl || data.baseUrl || '-';
    document.getElementById('infoFrontend').textContent = frontendUrls.length ? frontendUrls.join(' , ') : '-';
    document.getElementById('infoMain').textContent = settings.mainUrl || '-';

    // Settings inputs (only set value if not focused / not mid-editing)
    const editing = document.activeElement && (
      document.activeElement.classList?.contains('input-frontend') ||
      document.activeElement.id === 'btnAddFrontend'
    );
    if (!editing) renderFrontendRows(frontendUrls);

    const me = document.getElementById('inputMain');
    const be = document.getElementById('inputBackend');
    if (me !== document.activeElement) me.value = settings.mainUrl || '';
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

  // Auto-save defaults on first run so background/content always see a valid config
  chrome.storage.local.get(['settings'], (data) => {
    if (!data.settings) {
      chrome.storage.local.set({ settings: DEFAULT_SETTINGS }).catch(() => {});
    }
  });

  // Initial load
  chrome.storage.local.get(['token', 'sessionId', 'displayName', 'baseUrl', 'connected', 'settings', 'logs'], render);

  // Listen for changes
  chrome.storage.onChanged.addListener(() => {
    chrome.storage.local.get(['token', 'sessionId', 'displayName', 'baseUrl', 'connected', 'settings', 'logs'], render);
  });
})();

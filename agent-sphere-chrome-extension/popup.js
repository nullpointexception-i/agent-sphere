(function () {
  const DEFAULT_SETTINGS = { frontendUrls: ['https://bole.buukle.top'], mainUrl: 'https://as.buukle.top', backendUrl: 'https://as.buukle.top', agentCursor: true, browserMetrics: false, browserMetricAudit: false, browserMetricSample: 5 };

  // ---- 版本与升级提示 ----
  // 扩展是「Load unpacked」安装（Release 附件 zip），manifest 无 update_url，Chrome 不会自动更新。
  // 后端通过公开配置 plugin.min-extension-version 声明最低要求版本，这里比对后提示用户重新下载。
  // 注意：发布包版本由 deploy.yml 从 tag 生成（v1.0.NNN-alpha → 1.0.NNN）。
  const CURRENT_VERSION = chrome.runtime.getManifest().version;
  const UPDATE_CHECK_TTL_MS = 10 * 60 * 1000;
  // render 会被 browserMetrics 等写入频繁触发，必须缓存，否则每次动作都会打一次公开配置接口
  let updateCheckState = { key: '', at: 0 };

  /** 按点分段比较版本号：返回 -1 / 0 / 1；缺失段按 0 处理。 */
  function compareVersions(a, b) {
    const pa = String(a || '').split('.').map((x) => parseInt(x, 10) || 0);
    const pb = String(b || '').split('.').map((x) => parseInt(x, 10) || 0);
    const n = Math.max(pa.length, pb.length);
    for (let i = 0; i < n; i++) {
      const x = pa[i] || 0;
      const y = pb[i] || 0;
      if (x !== y) return x < y ? -1 : 1;
    }
    return 0;
  }

  /** plugin.download-url 可能是相对路由（V47 起存 apiBase 相对路径），需要拼成绝对地址。 */
  function resolveDownloadUrl(raw, backend) {
    if (!raw) return '';
    if (/^https?:\/\//i.test(raw)) return raw;
    const base = (backend || '').replace(/\/+$/, '');
    if (!base) return '';
    return `${base}/api/v1${raw.startsWith('/') ? raw : `/${raw}`}`;
  }

  async function checkForUpdate(settings) {
    const banner = document.getElementById('updateBanner');
    if (!banner) return;
    const backend = (settings && settings.backendUrl) || '';
    if (!backend) {
      banner.style.display = 'none';
      return;
    }
    const now = Date.now();
    if (updateCheckState.key === backend && now - updateCheckState.at < UPDATE_CHECK_TTL_MS) {
      return; // 缓存期内不重复请求
    }
    updateCheckState = { key: backend, at: now };
    try {
      const url = `${backend.replace(/\/+$/, '')}/api/v1/system/config/public`
        + '?keys=plugin.min-extension-version,plugin.download-url';
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const cfg = (await res.json()) || {};
      const min = String(cfg['plugin.min-extension-version'] || '').trim();
      if (!min || compareVersions(CURRENT_VERSION, min) >= 0) {
        banner.style.display = 'none';
        return;
      }
      document.getElementById('updateBannerText').textContent =
        `扩展有新版本（当前 ${CURRENT_VERSION}，最低要求 ${min}）。请重新下载并 Reload。`;
      const link = document.getElementById('updateBannerLink');
      const dl = resolveDownloadUrl(String(cfg['plugin.download-url'] || '').trim(), backend);
      if (dl) {
        link.href = dl;
        link.style.display = '';
      } else {
        link.style.display = 'none';
      }
      banner.style.display = '';
    } catch (e) {
      // 拉不到就不提示：升级提示是辅助信息，不能因网络或旧后端而骚扰用户
      banner.style.display = 'none';
    }
  }

  // Metrics tab state. Refresh only runs while the tab is open — the background
  // writes metrics on every action, and an always-on listener would re-render the
  // whole popup (and reset list scroll) while the user reads it.
  let activeTab = 'info';
  let metricsFilter = 'all';
  let metricsTimer = null;
  let metricsEnabled = false;
  const BM_KEYS = ['browserMetrics', 'browserMetricsRecent'];
  const FILTER_CAP = 20;

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
      activeTab = tab.dataset.tab;
      if (activeTab === 'metrics') scheduleMetricsRefresh();
    });
  });

  // --- Frontend URL rows (dynamic multi-row) ---
  function createFrontendRow(value) {
    const row = document.createElement('div');
    row.className = 'frontend-row';
    const input = document.createElement('input');
    input.type = 'url';
    input.className = 'input-frontend';
    input.placeholder = 'https://bole.buukle.top';
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
      agentCursor: document.getElementById('inputAgentCursor').checked,
      browserMetrics: document.getElementById('inputBrowserMetrics').checked,
      browserMetricAudit: document.getElementById('inputMetricAudit').checked,
      browserMetricSample: (() => {
        const v = parseFloat(document.getElementById('inputMetricSample').value);
        return Number.isFinite(v) && v > 0 && v <= 100 ? v : DEFAULT_SETTINGS.browserMetricSample;
      })(),
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

  // --- Copy a log line (delegated: rows are re-rendered wholesale on every log) ---
  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      // clipboardWrite 缺失或非聚焦态时回退；execCommand 需在同一用户手势的同步栈内
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        ta.remove();
        return ok;
      } catch (e2) {
        return false;
      }
    }
  }

  document.getElementById('logContainer').addEventListener('click', async (e) => {
    const row = e.target.closest('.log-row');
    if (!row) return;
    // data-line 是完整整行（含被 ellipsis 隐藏的部分）—— 截断只是视觉上的。
    const ok = await copyText(row.dataset.line || row.textContent || '');
    row.classList.remove('copied', 'copy-failed');
    void row.offsetWidth; // 强制 reflow，让同一行的重复点击也能重放过渡
    row.classList.add(ok ? 'copied' : 'copy-failed');
    setTimeout(() => row.classList.remove('copied', 'copy-failed'), 1000);
  });

  // --- Metrics: helper renderers ---

  function pctText(v) { return v == null ? '-' : v.toFixed(1) + '%'; }

  function setBar(id, pct, tone) {
    const bar = document.getElementById(id);
    if (!bar) return;
    bar.style.width = (pct == null ? 0 : Math.max(0, Math.min(100, pct))) + '%';
    const wrap = bar.parentElement;
    wrap.className = 'm-bar' + (tone ? ' ' + tone : '');
  }

  function fmtMs(ms) {
    if (ms == null) return '-';
    return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : Math.round(ms) + 'ms';
  }

  // Mis-click / echo-wrong are "lower is better", so they get the only
  // red→amber→green treatment in this panel. Success rates stay blue: colouring
  // them would imply pass/fail thresholds we haven't agreed on.
  function lowIsBetterTone(pct) {
    if (pct == null) return null;
    if (pct <= 2) return null;
    if (pct <= 5) return 'warn';
    return 'bad';
  }

  const CAT_TONE = {
    not_found: 'red', occluded: 'red', ambiguous: 'amber',
    not_interactable: 'amber', timeout: 'gray',
  };

  function fmtTime(ts) {
    return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  function renderSpark(latencies) {
    const svg = document.getElementById('mSpark');
    if (!svg) return;
    const vals = latencies || [];
    if (vals.length < 2) { svg.innerHTML = ''; return; }
    const max = Math.max.apply(null, vals) || 1;
    const step = 100 / (vals.length - 1);
    let d = '';
    for (let i = 0; i < vals.length; i++) {
      const x = (i * step).toFixed(2);
      const y = (19 - (vals[i] / max) * 18).toFixed(2);
      d += (i === 0 ? 'M' : 'L') + x + ' ' + y;
    }
    svg.innerHTML = '<path d="' + d + '" fill="none" stroke="#0a84ff" stroke-width="1" vector-effect="non-scaling-stroke"/>';
  }

  function renderCategories(byCategory) {
    const box = document.getElementById('mCats');
    const note = document.getElementById('mFailN');
    if (!box) return;
    const entries = Object.keys(byCategory || {})
      .map(k => [k, byCategory[k]])
      .filter(e => e[1] > 0)
      .sort((a, b) => b[1] - a[1]);
    const total = entries.reduce((n, e) => n + e[1], 0);
    note.textContent = total ? '(' + total + ')' : '';
    if (!entries.length) { box.innerHTML = '<div class="empty">No failures recorded</div>'; return; }
    const max = entries[0][1];
    box.innerHTML = entries.map(([k, n]) => {
      const tone = CAT_TONE[k] || 'gray';
      const w = Math.max(2, Math.round((n / max) * 100));
      return '<div class="m-cat"><span>' + esc(k) + '</span><span class="b ' + tone + '" style="width:' + w + '%;flex:1"></span><span class="n">' + n + '</span></div>';
    }).join('');
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  const VERDICTS = [
    { v: 'correct', s: '✓', t: '✓ Clicked the intended element' },
    { v: 'wrong_element', s: '✗', t: '✗ Clicked the wrong element' },
    { v: 'no_effect', s: '○', t: '○ No effect on the page' },
  ];

  function renderRecent(rows) {
    const box = document.getElementById('mList');
    const note = document.getElementById('mRecentN');
    if (!box) return;
    if (!rows.length) { note.textContent = ''; box.innerHTML = '<div class="empty">Nothing matches this filter</div>'; return; }
    note.textContent = '(' + rows.length + ')';
    box.innerHTML = rows.map(r => {
      const suspect = r.changed === true && r.selfChanged === false;
      const target = r.tag ? '&lt;' + esc(r.tag) + '&gt;' : (r.cat || 'navigate');
      const chips = [];
      if (r.alignUsed != null) chips.push('a' + r.alignUsed);
      if (r.hitPath && r.hitPath !== 'none') chips.push(r.hitPath);
      if (r.changed === true) chips.push('changed');
      if (r.selfChanged === true) chips.push('selfChanged');
      if (r.echoOk === true) chips.push('echo ok');
      if (r.ms != null) chips.push(fmtMs(r.ms));
      const votes = VERDICTS.map(v =>
        '<button class="m-vote' + (r.verdict === v.v ? ' on' : '') + '" data-verdict="' + v.v +
        '" data-t="' + r.t + '" data-cmd="' + esc(r.commandId) + '" title="' + v.t + '">' + v.s + '</button>'
      ).join('');
      return '<div class="m-item">' +
        '<div class="m-item-h"><span class="m-item-t">' + fmtTime(r.t) + '</span>' +
          '<span class="m-item-a">' + esc(r.action) + (r.ok ? '' : ' ✗') + '</span>' +
          (suspect ? '<span class="m-chip flag">mis-click?</span>' : '') +
          '<span class="m-votes">' + votes + '</span></div>' +
        '<div class="m-item-d">' + target + (r.text ? ' ' + esc(r.text) : '') + '</div>' +
        '<div class="m-item-d">' + chips.map(c => '<span class="m-chip">' + c + '</span>').join('') + '</div>' +
      '</div>';
    }).join('');
  }

  function renderMetrics(state, recent) {
    const empty = document.getElementById('mEmpty');
    const body = document.getElementById('mBody');
    const listCard = document.getElementById('mList');
    const s = state || null;
    const has = !!(s && s.counters && s.counters.ops > 0);
    const enabled = metricsEnabled;
    if (empty) empty.style.display = has ? 'none' : 'block';
    if (body) body.style.display = has ? 'block' : 'none';
    if (listCard) listCard.closest('.card').style.display = has ? 'block' : 'none';

    const stamp = document.getElementById('mState');
    if (stamp) stamp.textContent = enabled ? 'Collecting' : 'Paused (toggle off)';
    if (!has) { renderRecent([]); return; }

    const snap = snapshotOf(s);
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };

    set('mOps', String(s.counters.ops) + ' (' + s.counters.ok + ' ok / ' + s.counters.fail + ' fail)');
    set('mSince', new Date(s.startedAt).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }));
    set('mDecidable', pctText(snap.decidableRate));

    set('mExec', pctText(snap.execRate));       setBar('mExecBar', snap.execRate);
    set('mEffect', pctText(snap.effectRate));   setBar('mEffectBar', snap.effectRate);
    set('mType', pctText(snap.typeRate));       setBar('mTypeBar', snap.typeRate);

    const a0 = s.byAlign['0'] || 0, a1 = s.byAlign['1'] || 0, a2 = s.byAlign['2'] || 0;
    const at = a0 + a1 + a2 || 1;
    document.getElementById('mA0').style.width = (a0 / at * 100) + '%';
    document.getElementById('mA1').style.width = (a1 / at * 100) + '%';
    document.getElementById('mA2').style.width = (a2 / at * 100) + '%';
    set('mA0n', a0); set('mA1n', a1); set('mA2n', a2);
    set('mRescued', pctText(at > 1 ? (a1 + a2) / at * 100 : null));

    set('mMis', pctText(snap.misclickRate));    setBar('mMisBar', snap.misclickRate, lowIsBetterTone(snap.misclickRate));
    set('mEcho', pctText(snap.echoFailRate));   setBar('mEchoBar', snap.echoFailRate, lowIsBetterTone(snap.echoFailRate));
    set('mDegr', pctText(snap.degradedRate));
    set('mRetgt', pctText(snap.retargetRate));
    set('mUnset', pctText(snap.unsettledRate));
    set('mCross', pctText(snap.crossFrameRate));

    renderCategories(s.byCategory);

    set('mP50', fmtMs(snap.lat.p50));
    set('mP95', fmtMs(snap.lat.p95));
    set('mMax', fmtMs(snap.lat.max));
    renderSpark(s.latencies);

    set('mSessN', '(' + snap.sessions.count + ')');
    set('mFirstTry', pctText(snap.sessions.firstTryRate));
    set('mAvgOps', snap.sessions.avgOps == null ? '-' : snap.sessions.avgOps.toFixed(1));

    renderRecent(filterRecent(recent || []));
  }

  // Mirrors buildSnapshot() in lib/browser-metrics.js. Duplicated deliberately:
  // the popup can't import an ESM module, and the background only owns writing.
  function snapshotOf(s) {
    const c = s.counters;
    const type = s.byAction.type || { ops: 0, ok: 0 };
    const pct = (n, d) => (d ? (n / d) * 100 : null);
    const decidable = c.selfChanged + c.selfChangedUnknown;
    const sorted = (s.latencies || []).slice().sort((a, b) => a - b);
    const pick = p => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(p / 100 * sorted.length) - 1))] : null);
    let sc = 0, fok = 0, tops = 0;
    for (const k of Object.keys(s.sessions || {})) { const v = s.sessions[k]; sc++; tops += v.ops; if (v.firstOk) fok++; }
    const a0 = s.byAlign['0'] || 0, a1 = s.byAlign['1'] || 0, a2 = s.byAlign['2'] || 0;
    const aT = a0 + a1 + a2;
    return {
      execRate: pct(c.ok, c.ops),
      effectRate: pct(c.effectCounted, c.ok),
      typeRate: pct(c.echoOk, type.ok),
      decidableRate: pct(c.selfChanged, decidable),
      misclickRate: pct(c.misclickSuspect, c.selfChanged),
      echoFailRate: pct(c.echoFail, type.ok),
      degradedRate: pct(c.degraded, c.ops),
      retargetRate: pct(c.retargeted, c.ops),
      unsettledRate: pct(c.settledFail, c.ops),
      crossFrameRate: pct(s.byHitPath.cdp, c.ops),
      lat: { p50: pick(50), p95: pick(95), max: sorted.length ? sorted[sorted.length - 1] : null },
      sessions: { count: sc, firstTryRate: pct(fok, sc), avgOps: sc ? tops / sc : null },
    };
  }

  function filterRecent(rows) {
    let out = rows;
    if (metricsFilter === 'suspect') out = out.filter(r => r.changed === true && r.selfChanged === false);
    else if (metricsFilter === 'failed') out = out.filter(r => !r.ok);
    else if (metricsFilter === 'unaudited') out = out.filter(r => r.needsAudit && !r.verdict);
    return out.slice(0, FILTER_CAP);
  }

  function scheduleMetricsRefresh() {
    if (activeTab !== 'metrics') return;
    clearTimeout(metricsTimer);
    metricsTimer = setTimeout(() => {
      if (activeTab !== 'metrics') return;
      chrome.storage.local.get(['settings', 'browserMetrics', 'browserMetricsRecent'], (d) => {
        metricsEnabled = !!(d.settings && d.settings.browserMetrics);
        renderMetrics(d.browserMetrics, d.browserMetricsRecent);
      });
    }, 250);
  }

  // --- Metrics: list interactions (delegated — 20 rows × 3 buttons) ---
  document.querySelectorAll('.m-chipbtn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.m-chipbtn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      metricsFilter = btn.dataset.filter;
      scheduleMetricsRefresh();
    });
  });

  document.getElementById('mList').addEventListener('click', (e) => {
    const btn = e.target.closest('.m-vote');
    if (!btn) return;
    const t = Number(btn.dataset.t), cmd = btn.dataset.cmd, v = btn.dataset.verdict;
    chrome.storage.local.get(['browserMetricsRecent'], (d) => {
      const rows = d.browserMetricsRecent || [];
      const hit = rows.find(r => r.t === t && r.commandId === cmd);
      if (!hit) return;
      hit.verdict = hit.verdict === v ? null : v;   // click again to clear
      chrome.storage.local.set({ browserMetricsRecent: rows }).then(scheduleMetricsRefresh);
    });
  });

  document.getElementById('mClear').addEventListener('click', () => {
    if (!confirm('Clear all collected browser metrics? This cannot be undone.')) return;
    chrome.storage.local.remove(BM_KEYS).then(scheduleMetricsRefresh);
  });

  document.getElementById('mExport').addEventListener('click', () => {
    chrome.storage.local.get(BM_KEYS, (d) => {
      const blob = new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), state: d.browserMetrics || null, recent: d.browserMetricsRecent || [] }, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'agentsphere-browser-metrics-' + new Date().toISOString().slice(0, 10) + '.json';
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 2000);
    });
  });

  // The audit rate only means something while manual auditing is switched on, and
  // the rate itself only matters when metrics collection is running at all. Dimming
  // the whole group beats disabling a single input — a greyed-out number field next to
  // a live switch reads as broken rather than inactive.
  function syncAuditGroup() {
    const collect = document.getElementById('inputBrowserMetrics');
    const audit = document.getElementById('inputMetricAudit');
    const rate = document.getElementById('inputMetricSample');
    const group = document.getElementById('auditGroup');
    const on = !!(collect && collect.checked && audit && audit.checked);
    if (group) group.classList.toggle('off', !on);
    if (rate) {
      rate.disabled = !on;
      rate.style.opacity = on ? '1' : '0.45';
    }
  }

  ['inputBrowserMetrics', 'inputMetricAudit'].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.addEventListener('change', syncAuditGroup);
  });

  // --- Render ---
  function render(data) {
    const taskConnected = !!data.taskConnected;
    const settings = data.settings || DEFAULT_SETTINGS;
    const frontendUrls = getFrontendUrls(settings);

    // Status badge: user-level task connection
    const taskBadge = document.getElementById('taskStatusBadge');
    taskBadge.className = 'status-badge ' + (taskConnected ? 'on' : 'off');
    document.getElementById('taskStatusText').textContent = taskConnected ? 'Task On' : 'Task Off';

    // Info panel
    document.getElementById('infoUser').textContent = data.displayName || (data.token ? 'Logged in' : '-');
    document.getElementById('infoTaskConn').textContent = taskConnected ? 'On' : 'Off';
    document.getElementById('infoBackend').textContent = settings.backendUrl || data.baseUrl || '-';
    document.getElementById('infoVersion').textContent = CURRENT_VERSION;
    // 升级提示：命中失败/无最低版本要求时静默隐藏（内部有 10 分钟缓存，render 频繁也不会刷接口）
    void checkForUpdate(settings);
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
    const cur = document.getElementById('inputAgentCursor');
    if (me !== document.activeElement) me.value = settings.mainUrl || '';
    if (be !== document.activeElement) be.value = settings.backendUrl || '';
    if (cur) cur.checked = settings.agentCursor !== false;

    const bm = document.getElementById('inputBrowserMetrics');
    const ba = document.getElementById('inputMetricAudit');
    const bs = document.getElementById('inputMetricSample');
    metricsEnabled = settings.browserMetrics === true;
    if (bm) bm.checked = metricsEnabled;
    if (ba) ba.checked = settings.browserMetricAudit === true;
    if (bs && bs !== document.activeElement) {
      bs.value = String(settings.browserMetricSample != null ? settings.browserMetricSample : DEFAULT_SETTINGS.browserMetricSample);
    }
    syncAuditGroup();

    // Logs
    const logs = data.logs || [];
    const container = document.getElementById('logContainer');
    if (logs.length === 0) {
      container.innerHTML = '<div class="empty">No operations yet</div>';
    } else {
      container.innerHTML = logs.slice(-30).reverse().map(log => {
        const time = log.time ? new Date(log.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '';
        const detail = log.detail || '';
        // 必须转义：occluded 类错误本身就含尖括号（如 "<div#modal> intercepts the click"），
        // 不加 esc 会被 innerHTML 当标签解析 → 前半段消失、整行 DOM 结构被打乱。
        // 全文同时放进 title（悬停查看）与 data-line（点击复制整行）。
        const line = `${time} ${log.success ? 'OK' : 'FAIL'} ${log.action || ''}${detail ? ' — ' + detail : ''}`;
        return `<div class="log-row" data-line="${esc(line)}">`
          + `<span class="log-time">${esc(time)}</span>`
          + `<span class="log-icon">${log.success ? '✅' : '❌'}</span>`
          + `<span class="log-action">${esc(log.action)}</span>`
          + `<span class="log-detail" title="${esc(detail)}">${esc(detail)}</span>`
          + `</div>`;
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
  chrome.storage.local.get(['token', 'displayName', 'baseUrl', 'taskConnected', 'settings', 'logs'], render);

  // Listen for changes. browserMetrics/browserMetricsRecent are deliberately NOT
  // in the key list below — they change on every single action, and reading them
  // here would re-render the whole popup continuously. The Metrics tab pulls them
  // itself, and only while it is the visible tab.
  chrome.storage.onChanged.addListener(() => {
    chrome.storage.local.get(['token', 'displayName', 'baseUrl', 'taskConnected', 'settings', 'logs'], render);
    scheduleMetricsRefresh();
  });
})();

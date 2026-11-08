/**
 * Service Worker (MV3, ESM) — message router, command dispatch, tab grouping,
 * offscreen keepalive, and callback reporting to the backend.
 *
 * Long-lived task SSE lives in the offscreen document; this worker relays
 * browser_operation commands into tabs and reports results via HTTP callback.
 */
import { cdpClient } from './lib/cdp-client.js';
import { tabManager, FOCUS_RELEASE_ALARM } from './lib/tab-manager.js';
import { okResult, okWarning, failResult, ErrorCategory } from './lib/result.js';
import { ensureOffscreenDocument, askOffscreen } from './lib/offscreen-bridge.js';
import { loadSessionState, saveSessionState } from './lib/session-state.js';
import { agentCursor } from './lib/agent-cursor.js';
import { browserMetrics } from './lib/browser-metrics.js';

console.log('[AgentSphere] Service Worker started', new Date().toISOString());

let token = '';
let baseUrl = '';
/** Local device identifier (persisted in storage.local, stable across restarts) + readable name. */
let deviceId = '';
let deviceName = '';
/** Source surface of the current token (WIDGET/CONSOLE), reported with callbacks. */
let tokenSource = '';
/** commandId -> {runId, callId}: carried back by callbacks so the executing device is recorded on the tool row. */
const commandMeta = new Map();
/** Token that last triggered task:init: repeated reports of the same token from multiple tabs no longer reconnect. */
let lastInitToken = '';
const EXTENSION_VERSION = chrome.runtime.getManifest().version;
// Target origin of the last navigate: if the controlled tab leaves that site, refuse further actions (prevents wandering off).
// Persisted to storage.session - if kept only in memory, assertControlledHost silently stops working after an SW restart.
let targetOrigin = null;
// Command concurrency guard: commands left dangling while navigation rebuilds content scripts no longer pile up, avoiding a 10s empty wait on the backend.
// Uses a timestamp lease: it auto-releases after the single-command timeout (60s), so one stuck command cannot lock the whole session.
// 60s > the backend's 30s executeJS limit: if the lease expired before the command, the concurrency guard would let a second command through,
// with two commands operating the same tab at once.
const COMMAND_LEASE_MS = 60000;
// Extension-side deadline for executeJS. Deliberately 3s below the backend EXECUTEJS_TIMEOUT_SECONDS (30s):
// equal values create a race - if the backend times out first it returns immediately and releases the pending future while the extension is still running
// and still holds the lease; the late callback is silently dropped and the next command hits the concurrency guard again.
// 27s lets the extension reliably return a clean errorCategory='timeout' first and release the lease normally.
const EXECJS_TIMEOUT_MS = 27000;
let commandInFlightAt = 0;
// The command currently running (action + start time), so a guard rejection can explain what is executing and how long until it is released,
// letting the backend LLM decide whether to wait or verify with getContent first instead of firing blindly again and again.
let commandActive = null;

// ---- Risk-control hit (unattended: stop on hit) ----
// Detection: the controlled tab was redirected to a security center/captcha page. On a hit it **locks per session**;
// all later commands are rejected - consistent with the product rule "stop on hit, no waiting/retry/detour".
// The host pattern is deliberately generic, with no specific site brand: a `//safe.` host prefix + captcha/intercept path fragments
// already covers real risk-control redirects. If a site's risk-control domain does not take this shape, read it from configuration instead of hardcoding a brand.
const RISK_URL_PATTERNS = [/\/\/safe\./i, /captchaPage/i, /\/v\/intercept\//i];
const RISK_TITLE_PATTERNS = [/验证码/, /安全中心/];
const riskLockedSessions = new Set();

async function detectRiskChallenge(tabId) {
  if (!tabId) return null;
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  if (!tab) return null;
  const url = tab.url || '';
  const title = tab.title || '';
  const hit = RISK_URL_PATTERNS.some((re) => re.test(url))
    || RISK_TITLE_PATTERNS.some((re) => re.test(title));
  return hit ? { url, title } : null;
}

function defaultDeviceName() {
  const ua = navigator.userAgent || '';
  const browser = /Edg\//.test(ua) ? 'Edge'
    : /Chrome\//.test(ua) ? 'Chrome'
    : /Firefox\//.test(ua) ? 'Firefox' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows'
    : /Mac OS X/.test(ua) ? 'macOS'
    : /Linux/.test(ua) ? 'Linux' : 'Unknown';
  return `${browser} · ${os}`;
}

/** Generate the device ID on first start; reuse it across restarts (same browser = same device). */
function ensureDeviceIdentity() {
  chrome.storage.local.get(['deviceId', 'deviceName'], (data) => {
    let changed = false;
    if (data.deviceId) {
      deviceId = data.deviceId;
    } else {
      deviceId = (crypto.randomUUID && crypto.randomUUID())
        || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      changed = true;
    }
    if (data.deviceName) {
      deviceName = data.deviceName;
    } else {
      deviceName = defaultDeviceName();
      changed = true;
    }
    if (changed) {
      chrome.storage.local.set({ deviceId, deviceName }).catch(() => {});
    }
  });
}
ensureDeviceIdentity();

// --- Keep the offscreen document (SSE host) alive / recreate if the browser closed it ---
function startOffscreenKeepalive() {
  chrome.alarms.create('offscreen-keepalive', { periodInMinutes: 1 });
  chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === 'offscreen-keepalive') {
      ensureOffscreenDocument().then((ok) => {
        if (!ok) console.warn('[AgentSphere] Offscreen document unavailable');
      });
    }
    // Durable fallback when the service worker was evicted before the idle setTimeout fired.
    if (alarm.name === FOCUS_RELEASE_ALARM) {
      void tabManager.releaseFocus();
    }
  });
}
startOffscreenKeepalive();

function startSseHost() {
  chrome.storage.local.get(['token', 'baseUrl', 'tokenSource'], (data) => {
    if (data.token && data.baseUrl) {
      token = data.token;
      baseUrl = data.baseUrl;
      tokenSource = data.tokenSource || '';
    }
    ensureOffscreenDocument();
    // Self-heal after SW restart: re-inject into already-open matching tabs
    loadConfiguredUrls(() => injectMatchingTabs());
    // Self-heal after SW restart: restore the controlled-site guard and re-aggregate
    // the plugin's tabs into the AgentSphere group (both live in storage.session).
    loadSessionState().then((st) => {
      if (st.targetOrigin) targetOrigin = st.targetOrigin;
      return tabManager.regroupControlledTabs();
    }).catch((e) => console.warn('[AgentSphere] session restore failed:', e?.message));
  });
}
startSseHost();

// --- Configured URLs (popup Settings): widget hosts (frontendUrls[]) + main site (mainUrl) ---
let frontendUrls = [];
let mainUrl = '';

/**
 * Address that used to be the default (the old value before switching to https).
 *
 * <p>Only **exact matches** are rewritten: custom domains, addresses with ports, canary/internal addresses are never touched.
 * If a user deliberately kept http (internal deployment), it is rewritten to https too - these two domains are the public production addresses,
 * and that trade-off is acceptable; users who need http just fill it in again.
 */
const LEGACY_DEFAULT_URLS = {
  'http://bole.buukle.top': 'https://bole.buukle.top',
  'http://as.buukle.top': 'https://as.buukle.top',
};

/**
 * Migrate the old default addresses in storage to https; returns null when no change is needed.
 *
 * <p>Idempotent: once migrated and written back the old literals no longer exist, so the next call returns null and no write-change-write loop forms.
 */
function upgradeLegacySettings(settings) {
  if (!settings || typeof settings !== 'object') return null;
  let changed = false;
  const mapUrl = (u) => {
    if (typeof u !== 'string') return u;
    const hit = LEGACY_DEFAULT_URLS[u.trim()];
    if (!hit) return u;
    changed = true;
    return hit;
  };
  const next = { ...settings };
  if (Array.isArray(settings.frontendUrls)) next.frontendUrls = settings.frontendUrls.map(mapUrl);
  if (settings.frontendUrl != null) next.frontendUrl = mapUrl(settings.frontendUrl);
  if (settings.mainUrl != null) next.mainUrl = mapUrl(settings.mainUrl);
  if (settings.backendUrl != null) next.backendUrl = mapUrl(settings.backendUrl);
  return changed ? next : null;
}

function loadConfiguredUrls(done) {
  chrome.storage.local.get(['settings'], (data) => {
    // One-time migration: old http defaults -> https. Placed on the service worker's always-on startup path,
    // so it runs even if the user never opens the popup; the popup's storage.onChanged then re-reads and re-renders automatically.
    const upgraded = upgradeLegacySettings(data.settings);
    if (upgraded) {
      chrome.storage.local.set({ settings: upgraded }).catch(() => {});
      console.log('[AgentSphere] Migrated legacy http defaults to https');
    }
    const s = upgraded || data.settings || {};
    frontendUrls = Array.isArray(s.frontendUrls)
      ? s.frontendUrls.filter(Boolean)
      : s.frontendUrl
        ? [s.frontendUrl]
        : [];
    mainUrl = s.mainUrl || '';
    // Cursor trail is on by default (only an explicit false turns it off)
    agentCursor.setEnabled(s.agentCursor !== false);
    // Hidden/occluded agent windows are throttled by Chrome; default auto-focuses only when that happens.
    tabManager.setBackgroundFocusMode(s.backgroundFocusMode);
    // Browser operation metrics are **off** by default: data stays in local storage.local, but details contain page element text,
    // so the user must opt in explicitly. When disabled, record* returns on the first line with zero collection overhead.
    browserMetrics.setEnabled(s.browserMetrics === true);
    // The UI stores 0-100 percentages; the module uses a 0-1 ratio internally
    browserMetrics.setSampleRate(s.browserMetricAudit === true
      ? Math.max(0, Math.min(100, Number(s.browserMetricSample) || 5)) / 100
      : 0);
    console.log('[AgentSphere] Config:', JSON.stringify({ frontendUrls, mainUrl, agentCursor: agentCursor.enabled, browserMetrics: browserMetrics.isEnabled() }));
    injectMatchingTabs();
    if (typeof done === 'function') done();
  });
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.settings) {
    const s = changes.settings.newValue || {};
    frontendUrls = Array.isArray(s.frontendUrls)
      ? s.frontendUrls.filter(Boolean)
      : s.frontendUrl
        ? [s.frontendUrl]
        : [];
    mainUrl = s.mainUrl || '';
    agentCursor.setEnabled(s.agentCursor !== false);
    tabManager.setBackgroundFocusMode(s.backgroundFocusMode);
    browserMetrics.setEnabled(s.browserMetrics === true);
    browserMetrics.setSampleRate(s.browserMetricAudit === true
      ? Math.max(0, Math.min(100, Number(s.browserMetricSample) || 5)) / 100
      : 0);
    console.log('[AgentSphere] Settings changed:', JSON.stringify({ frontendUrls, mainUrl, agentCursor: agentCursor.enabled, browserMetrics: browserMetrics.isEnabled() }));
    injectMatchingTabs();
  }
});

function shouldInject(url) {
  if (!url) return false;
  if (/\/chat\/\d+/.test(url)) return true;
  if (mainUrl && url.startsWith(mainUrl)) return true;
  for (const u of frontendUrls) {
    if (u && url.startsWith(u)) return true;
  }
  return false;
}

function injectMatchingTabs() {
  chrome.tabs.query({}, (tabs) => {
    for (const tab of tabs) {
      if (tab.id != null && shouldInject(tab.url || '')) {
        tabManager.injectContentScript(tab.id);
      }
    }
  });
}

// --- Detect session changes from tab URL (works even without content script) ---
chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (!changeInfo.url) return;
  if (shouldInject(changeInfo.url)) tabManager.injectContentScript(tabId);
});

// --- Detect SPA route changes (pushState/replaceState) for injection ---
chrome.webNavigation.onHistoryStateUpdated.addListener((details) => {
  if (!details.url) return;
  if (shouldInject(details.url)) tabManager.injectContentScript(details.tabId);
});

// --- When switching to an already-open matching tab: inject (ensure bridge present) ---
chrome.tabs.onActivated.addListener((activeInfo) => {
  chrome.tabs.get(activeInfo.tabId, (tab) => {
    if (!tab || tab.id == null || !shouldInject(tab.url || '')) return;
    tabManager.injectContentScript(tab.id);
  });
});

// --- Message router ---
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  // offscreen → background: a browser_operation command arrived on the SSE stream
  if (msg && msg.target === 'background' && msg.action === 'task:command') {
    const { commandId, action, params, sessionId, runId, callId } = msg.cmd;
    if (commandId) commandMeta.set(commandId, { runId, callId });
    executeInPage(commandId, action, params, sessionId).catch(() => {});
    return false; // fire-and-forget; the result is reported via HTTP callback
  }

  // offscreen → background: pull credentials (offscreen has no chrome.storage)
  if (msg && msg.target === 'background' && msg.action === 'task:creds') {
    (async () => {
      let t = token;
      let b = baseUrl;
      if (!t || !b) {
        const d = await chrome.storage.local.get(['token', 'baseUrl']);
        t = d.token || '';
        b = d.baseUrl || '';
        if (t && b) {
          token = t;
          baseUrl = b;
        }
      }
      sendResponse({ token: t, baseUrl: b, deviceId, deviceName });
    })();
    return true; // async response
  }

  // offscreen → background: report task connection status (popup badge)
  if (msg && msg.target === 'background' && msg.action === 'task:status') {
    chrome.storage.local.set({ taskConnected: !!msg.connected }).catch(() => {});
    if (msg.connected) syncConnections().catch(() => {});
    return false;
  }

  // content script → background: auth
  if ((msg.type === 'auth' || msg.type === 'auth_token') && msg.token) {
    token = msg.token;
    baseUrl = msg.baseUrl || baseUrl;
    tokenSource = msg.source || tokenSource;
    chrome.storage.local.set({
      token,
      baseUrl,
      tokenSource,
      displayName: msg.displayName || '',
    }).catch(() => {});
    console.log('[AgentSphere] auth: task connection established (user-level)');
    fetchSsoDisplayName();
    // Multiple tabs each report the same token: only rebuild the connection when the token changes,
    // otherwise every report opens a new SSE and the server piles up many dead connections.
    if (token !== lastInitToken) {
      lastInitToken = token;
      ensureOffscreenDocument().then(() => {
        askOffscreen({ action: 'task:init' });
      });
    }
  }

  // content -> background: the user selected an execution device in the overlay
  if (msg.type === 'chooseDevice' && msg.deviceId) {
    (async () => {
      try {
        await fetch(`${baseUrl}/api/v1/runtime/user/connections/preferred`, {
          method: 'PUT',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ deviceId: msg.deviceId }),
        });
      } catch (e) {
        console.warn('[AgentSphere] save preferred device failed:', e.message);
      }
      if (msg.deviceId === deviceId) {
        ensureOffscreenDocument().then(() => askOffscreen({ action: 'task:init' }));
      }
      sendResponse({ ok: true });
    })();
    return true;
  }

  if (msg.type === 'log') {
    chrome.storage.local.get(['logs'], (data) => {
      const logs = data.logs || [];
      logs.push({ time: msg.time || Date.now(), action: msg.action, success: msg.success, detail: msg.detail });
      if (logs.length > 200) logs.splice(0, logs.length - 200);
      chrome.storage.local.set({ logs }).catch(() => {});
    });
    return;
  }
  return false;
});

// --- SSO display name: providerCode@subject (such as bole@elvin), deduplicated by token ---
let ssoDisplayToken = '';

function fetchSsoDisplayName() {
  if (!token || !baseUrl || token === ssoDisplayToken) return;
  ssoDisplayToken = token;
  fetch(`${baseUrl}/api/v1/sso/me`, {
    headers: { 'Authorization': `Bearer ${token}` },
  })
    .then((resp) => (resp.ok ? resp.json() : null))
    .then((body) => {
      if (body && body.providerCode && body.subject) {
        const displayName = `${body.providerCode}@${body.subject}`;
        chrome.storage.local.set({ displayName }).catch(() => {});
      }
    })
    .catch(() => {});
}

// --- Helpers ---
/**
 * Fallback target tab: only accepts a tab that is "controlled and actually inside the plugin window".
 * The old implementation fell back to any active tab in the window - the plugin window is globally shared, so that treated a page
 * left over from the previous session (possibly another site) as this session's target page. Better to return null and report NO_TAB
 * than to read a page that does not belong to this session.
 */
async function getActiveTabId() {
  const controlled = tabManager.getControlled();
  if (controlled != null && await tabManager.isPluginTab(controlled)) return controlled;
  return null;
}

/** Resolve the target tab for this action: an explicit tabId must belong to the plugin window, otherwise user tabs are left untouched. */
async function resolveTargetTabId(params) {
  if (params?.tabId != null) {
    if (await tabManager.isPluginTab(params.tabId)) return params.tabId;
    // An explicit tabId may come from a closed old plugin window; leave user tabs alone and fall back to the current controlled plugin tab.
    const controlled = tabManager.getControlled();
    if (controlled != null && await tabManager.isPluginTab(controlled)) {
      return controlled;
    }
    return null;
  }
  const controlled = tabManager.getControlled();
  if (controlled != null && await tabManager.isPluginTab(controlled)) {
    return controlled;
  }
  return getActiveTabId();
}

async function waitIdle(tabId) {
  await chrome.scripting.executeScript({
    target: { tabId },
    func: () => new Promise((r) => requestIdleCallback(r, { timeout: 2000 })),
  }).catch(() => {});
}

function mapErrorCategory(e) {
  const m = String(e?.message || e);
  if (m.includes('No target tab')) return ErrorCategory.NO_TAB;
  if (m.includes('Receiving end does not exist') || m.includes('Content script unavailable')) return ErrorCategory.INJECT_FAILED;
  if (m.includes('not attached') || m.includes('already attached')) return ErrorCategory.DETACHED;
  if (m.includes('timed out')) return ErrorCategory.TIMEOUT;
  return ErrorCategory.UNKNOWN;
}

// --- Command execution ---
async function executeInPage(commandId, action, params, sessionId) {
  // Declare session ownership first: the plugin window is globally shared, so the previous session's tab must not be read/reused by this session.
  // This must run before the risk-control check - otherwise another session's tab title/URL would be used for the decision.
  tabManager.beginSession(sessionId);
  // Risk-control hit: lock per session, then reject every command outright (stop on hit, no waiting/retry/detour)
  const checkTabId = params.tabId || tabManager.getControlled();
  if (sessionId && riskLockedSessions.has(sessionId)) {
    const res = failResult('Risk-control hit (session locked): the page was redirected to a security center/captcha page. End the task immediately; do not retry or detour.', 'risk_challenge');
    sendCallbackSafe(commandId, res, checkTabId, sessionId);
    return;
  }
  const riskHit = await detectRiskChallenge(checkTabId);
  if (riskHit) {
    if (sessionId) riskLockedSessions.add(sessionId);
    const res = failResult(
      `Risk-control hit: the controlled page was redirected (url=${riskHit.url}). End the task now (status=failed); do not retry.`,
      'risk_challenge',
    );
    res.data = { _riskChallenge: true, _url: riskHit.url, _title: riskHit.title };
    sendCallbackSafe(commandId, res, checkTabId, sessionId);
    return;
  }

  if (commandInFlightAt && Date.now() - commandInFlightAt < COMMAND_LEASE_MS) {
    const elapsed = Date.now() - commandInFlightAt;
    const running = (commandActive && commandActive.action) || 'unknown';
    const retryAfterMs = Math.max(0, COMMAND_LEASE_MS - elapsed);
    const res = failResult(
      `Previous command still running (concurrency guard): ${running} has been running for ${Math.round(elapsed / 1000)}s and is released in about ${Math.ceil(retryAfterMs / 1000)}s; call getContent first to verify the current state`,
      ErrorCategory.UNKNOWN,
    );
    res.data = { _runningAction: running, elapsedMs: elapsed, retryAfterMs };
    sendCallbackSafe(commandId, res, tabManager.getControlled(), sessionId);
    return;
  }
  if (commandInFlightAt) {
    console.warn('[AgentSphere] command lease expired, releasing stuck guard');
  }
  commandInFlightAt = Date.now();
  commandActive = { action, at: commandInFlightAt };
  const t0 = Date.now();
  try {
    await executeInPageInner(commandId, action, params, sessionId);
  } finally {
    commandInFlightAt = 0;
    commandActive = null;
    console.log(`[ChromeCmd] action=${action} tab=${params.tabId || tabManager.getControlled() || '-'} ms=${Date.now() - t0}`);
  }
}

// The controlled tab must still be on the target site, otherwise refuse (run 26 once wandered off to localhost:8001).
async function assertControlledHost(tabId) {
  if (!targetOrigin) return null;
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  if (!tab || !tab.url) return null;
  try {
    const host = new URL(tab.url).host;
    if (host !== new URL(targetOrigin).host) {
      return `Controlled tab left the target site (actual ${tab.url}, target ${targetOrigin}). Navigate back to the main site before continuing; do not operate on the wrong page.`;
    }
  } catch (e) { /* ignore */ }
  return null;
}

const KEY_CODE_MAP = {
  enter: ['Enter', 'Enter', 13],
  escape: ['Escape', 'Escape', 27],
  tab: ['Tab', 'Tab', 9],
  arrowdown: ['ArrowDown', 'ArrowDown', 40],
  arrowup: ['ArrowUp', 'ArrowUp', 38],
  arrowleft: ['ArrowLeft', 'ArrowLeft', 37],
  arrowright: ['ArrowRight', 'ArrowRight', 39],
  backspace: ['Backspace', 'Backspace', 8],
  ' ': ['Space', 'Space', 32],
  space: ['Space', 'Space', 32],
};
function keyInfoOf(key) {
  const k = String(key == null ? 'Enter' : key).toLowerCase();
  const m = KEY_CODE_MAP[k] || ['Enter', 'Enter', 13];
  return { key: m[0], code: m[1], keyCode: m[2] };
}

// Write actions all go through CDP trusted input: the content script first locates across frames and computes main-viewport coordinates,
// then Input.dispatchMouseEvent / Input.insertText / Input.dispatchKeyEvent dispatch them (isTrusted=true).
const WRITE_ACTIONS = new Set(['click', 'type', 'key', 'hover']);

function hasLocator(params) {
  return !!(params && (params.selector || params.text || params.ref != null));
}

async function uploadScreenshotToBackend(base64, contentType) {
  const res = await fetch(`${baseUrl}/api/v1/browser/screenshot`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ contentType, base64 }),
  });
  if (!res.ok) {
    throw new Error(`Screenshot upload failed: HTTP ${res.status}`);
  }
  const body = await res.json();
  if (!body || !body.fileKey) {
    throw new Error('Screenshot upload returned no fileKey');
  }
  return body;
}

// Coordinate click: device pixels from the last viewport screenshot -> CSS px -> CDP trusted click;
// when no interactive element is hit, click the point anyway with a warning (real click semantics, faithful to the visual action).
async function clickAtPoint(commandId, params, targetTabId, sessionId) {
  const x = Number(params.x);
  const y = Number(params.y);
  if (!Number.isFinite(x) || !Number.isFinite(y)) {
    sendCallbackSafe(commandId, failResult('clickAt requires numeric x/y', ErrorCategory.NOT_FOUND), targetTabId, sessionId);
    return null;
  }
  let point = { x, y };
  let warning = null;
  try {
    const mapped = await tabManager.askContent(targetTabId, {
      type: 'browser_operation',
      action: 'locatePoint',
      params: { x, y, frameId: params.frameId || 0 },
    }, 2, params.frameId || 0, POST_ACTION_TIMEOUT_MS);
    const d = mapped && mapped.data;
    if (d && d.ok && d.x != null && d.y != null) {
      point = { x: d.x, y: d.y };
      if (d.interactive === false) {
        warning = 'Coordinate point hit no interactive element; clicked the raw coordinates directly';
      }
    } else if (d && d.ok === false) {
      warning = d.error || 'Failed to resolve the coordinate point; falling back to a raw click';
    }
  } catch (e) {
    warning = 'locatePoint failed; falling back to a raw click: ' + (e && e.message);
  }
  try {
    // Visual actions draw the cursor too: the coordinates come from the model reading the screenshot, but the user sees "the mouse moved there
    // and clicked", consistent with click/type and making it easier to tell whether the model missed.
    await agentCursor.show(targetTabId, point);
    await cdpClient.nativeClick(targetTabId, point.x, point.y);
  } catch (e) {
    console.warn('[AgentSphere] clickAt failed:', e?.message);
    sendCallbackSafe(commandId, failResult('clickAt failed: ' + e.message, mapErrorCategory(e)), targetTabId, sessionId);
    return null;
  }
  const data = { action: 'clickAt', x: point.x, y: point.y, _executed: true };
  if (warning) data.warning = warning;
  // post-action state: read back _url/_title/_hints (no preHash baseline, so changed is not invented)
  try {
    const v = await tabManager.askContent(targetTabId, {
      type: 'browser_operation',
      action: 'verify',
      params: { preHash: null, frameId: params.frameId || 0 },
    }, 2, params.frameId || 0, POST_ACTION_TIMEOUT_MS);
    const vd = v && v.data;
    if (vd) {
      if (vd._url) data._url = vd._url;
      if (vd._title) data._title = vd._title;
      if (vd._hints) Object.assign(data, vd._hints);
    }
  } catch (e) {
    console.warn('[AgentSphere] clickAt verify failed:', e?.message);
  }
  sendCallbackSafe(commandId, okResult(data, 'clickAt'), targetTabId, sessionId);
  return data;
}

async function locatePoint(tabId, params, timeoutMs) {
  let loc;
  try {
    loc = await tabManager.askContent(tabId, {
      type: 'browser_operation',
      action: 'locate',
      params,
    }, 2, params && params.frameId, timeoutMs);
  } catch (e) {
    return { ok: false, error: e.message, errorCategory: mapErrorCategory(e) };
  }
  if (!loc) {
    return { ok: false, error: 'no response from content script', errorCategory: ErrorCategory.INJECT_FAILED };
  }
  // The content script's structured failures (assertion failed / not settled) used to be discarded entirely: it returns
  // {success:false, error, errorCategory} with no data, so loc.data was undefined and
  // it returned null - information like 'Element is not enabled' could never surface,
  // and "target not actionable" and "target missing" were flattened into the same not_found in errorCategory.
  if (loc.success === false) {
    return {
      ok: false,
      error: loc.error,
      errorCategory: loc.errorCategory || ErrorCategory.NOT_FOUND,
      blockedBy: loc.blockedBy,
    };
  }
  const d = loc.data;
  if (!d) return { ok: false, error: 'empty locate result', errorCategory: ErrorCategory.INJECT_FAILED };
  if (d.ok === false) {
    return {
      ok: false,
      error: d.error,
      matches: d.matches,
      suggested: d.suggested,
      errorCategory: d.error === 'ambiguous' ? ErrorCategory.AMBIGUOUS : ErrorCategory.NOT_FOUND,
    };
  }
  if (!d.point || typeof d.point.x !== 'number' || typeof d.point.y !== 'number') return null;
  return {
    ok: true,
    point: d.point,
    tag: d.tag,
    text: d.text,
    count: d.count,
    preHash: d.preHash,
    selfFp: d.selfFp,
    selfRef: d.selfRef,
    retargeted: d.retargeted,
    settled: d.settled,
    clickable: d.clickable,
    hitOk: d.hitOk,
    hitDesc: d.hitDesc,
    ambiguous: d.ambiguous,
  };
}

async function cdpAct(tabId, action, params, loc) {
  switch (action) {
    case 'click':
      // Move the cursor to the target and wait for it to land (arrival barrier) before dispatching the click
      await agentCursor.show(tabId, loc && loc.point);
      await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y);
      break;
    case 'hover':
      await agentCursor.show(tabId, loc && loc.point);
      await cdpClient.nativeHover(tabId, loc.point.x, loc.point.y);
      break;
    case 'type':
      await agentCursor.show(tabId, loc && loc.point);
      await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y); // trusted focus first
      if (params.append !== true) await cdpClient.clearInput(tabId); // insertText only appends, so clear before replacing the whole field
      await cdpClient.typeText(tabId, params.text || ''); // type character by character (humanized)
      if (params.submit) await cdpClient.nativeKeyPress(tabId, 'Enter', 'Enter', 13);
      break;
    case 'key': {
      if (loc) {
        // Providing a locator triggers a trusted click to focus first - this step needs the cursor too
        await agentCursor.show(tabId, loc && loc.point);
        await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y);
      }
      const ki = keyInfoOf(params.key);
      await cdpClient.nativeKeyPress(tabId, ki.key, ki.code, ki.keyCode);
      break;
    }
  }
}

// Write action entry: locate -> CDP.
// Click/hover go through a three-tier scroll alignment + hit verification fallback loop (matching Codex's AZ three tiers +
// DOM.getNodeForLocation hit comparison) - when an element is covered by a sticky header / overlay, switching
// alignment tier often just works, far faster than erroring out and making the LLM start over.
// Only report an error when all tiers fail, name the interceptor in the message, and let errorCategory distinguish occluded / ambiguous / not_found.
const ALIGNMENTS = [
  { block: 'center', inline: 'nearest' },
  { block: 'end', inline: 'end' },
  { block: 'start', inline: 'start' },
];
const NEEDS_HIT_TEST = new Set(['click', 'hover']);
// Total budget for the three-tier fallback (ms). Deliberately **not** scaled with the backend's 30s executeJS timeout:
// the fallback addresses "did we click accurately", not "is the page slow". Each tier's waitMs already allows 2.5s
// of polling; if the budget scaled with the backend, the failure path would go from ~7s to ~32s, and the agent would burn
// 3x time on every idle round. Truly slow pages should use an explicit wait.
const ALIGN_RETRY_BUDGET_MS = 8000;
// Beyond the budget, leave room for: the cursor arrival barrier (<=1.5s, ~250ms in practice) + post-check 0.3s + callback return.
const RESERVE_MS = 2500;
// Fixed upper bound for post-check / read-only calls, so a stuck tab does not hold the command lease for long.
const POST_ACTION_TIMEOUT_MS = 4000;

/** Interceptor description (the first <tag#id.class> in interceptors). Used only for failure diagnostics and metrics. */
function firstInterceptor(interceptors) {
  for (const d of interceptors || []) {
    if (typeof d === 'string' && d.startsWith('<')) return d;
  }
  return null;
}

/**
 * Metric persistence for write actions. When the switch is off, browserMetrics.record* returns on the first line with zero overhead.
 * No new decision logic is added - this only carries over results the fallback loop/post-check already computed.
 */
function recordMetrics(commandId, action, params, sessionId, tabId, tStart, extra) {
  try {
    browserMetrics.recordWriteAction({
      commandId,
      sessionId,
      action,
      ms: Date.now() - tStart,
      ...extra,
    });
  } catch (e) {
    console.warn('[AgentSphere] recordMetrics failed:', e?.message);
  }
}

/** Metric persistence for navigate (duration + whether the tab was reused only). Zero overhead when the switch is off. */
function recordNavigate(commandId, sessionId, t0, extra) {
  try {
    browserMetrics.recordNavigate({
      commandId, sessionId, ms: Date.now() - t0, ...extra,
    });
  } catch (e) {
    console.warn('[AgentSphere] recordNavigate failed:', e?.message);
  }
}

async function writeAction(commandId, action, params, targetTabId, sessionId) {
  const deadline = Date.now() + (params.timeout > 0
    ? Math.max(500, params.timeout * 1000 - RESERVE_MS)
    : ALIGN_RETRY_BUDGET_MS);
  const interceptors = [];
  // The three alignment tiers of one command share a token: the content side uses it to reuse the element resolved by the first tier,
  // so the last two tiers skip resolveActionTarget (saves time and avoids drift from re-resolving by ref after a reflow).
  const reuseToken = 'r' + commandId + '_' + Date.now();
  let loc = null;
  let done = false;
  // Not-actionable failures: stop and report immediately, no further alignment tiers
  let notInteractable = null;
  // Metrics: these only "record results the fallback loop already computed" and produce no new decisions.
  let alignUsed = null;
  let alignsTried = 0;
  let hitPath = 'none';
  const tStart = Date.now();

  if (!hasLocator(params)) {
    if (action !== 'key') {
      sendCallbackSafe(commandId, failResult(action + ' requires selector/text/ref', ErrorCategory.NOT_FOUND), targetTabId, sessionId);
      return null;
    }
    done = true; // no locator for the key, take the original path
  } else {
    for (let i = 0; i < ALIGNMENTS.length && !done; i++) {
      alignsTried += 1;
      const left = deadline - Date.now();
      if (left <= 500) {
        interceptors.push('timeout');
        break;
      }
      // Split the remaining budget across each tier's polling wait, so the three tiers together do not blow the backend timeout
      const waitMs = Math.max(0, Math.min(2500, Math.floor(left / (ALIGNMENTS.length - i))));
      loc = await locatePoint(
        targetTabId,
        { ...params, align: ALIGNMENTS[i], waitMs, _reuseToken: reuseToken,
          needHitTest: NEEDS_HIT_TEST.has(action) },
        waitMs + 1500,
      );

      if (!loc || loc.ok === false) {
        // The target is currently not actionable (disabled / hidden / not settled): switching alignment tiers is pointless,
        // trying two more tiers only burns budget - stop and report the specific state to the caller.
        if (loc && loc.errorCategory === ErrorCategory.NOT_INTERACTABLE) {
          notInteractable = loc;
          break;
        }
        if (loc && loc.errorCategory === ErrorCategory.AMBIGUOUS) {
          interceptors.push('ambiguous:' + (loc.matches || 2));
        }
        continue; // not_found / ambiguous both move to the next tier
      }
      // Fallback succeeded but still matched multiple elements: flag it so the caller can judge whether the click missed
      if (loc.ambiguous && !interceptors.some((d) => String(d).startsWith('ambiguous:'))) {
        interceptors.push('ambiguous:' + loc.ambiguous.matches);
      }

      if (NEEDS_HIT_TEST.has(action)) {
        hitPath = loc.hitOk ? 'main' : 'cdp'; // main = elementFromPoint passed on the content side; cdp = cross-frame CDP verification was used
        let ok = loc.hitOk; // the main frame is already self-verified by elementFromPoint on the content side
        if (!ok && loc.hitDesc) interceptors.push(loc.hitDesc);
        if (!ok) {
          // Cross-origin iframe: elementFromPoint cannot reach it, so verify with CDP in main-viewport coordinates
          const ht = await cdpClient.hitTest(targetTabId, loc.point.x, loc.point.y);
          if (ht.hit && ht.desc) {
            interceptors.push(ht.desc);
            // The hit element has the same tag as the target -> treat it as a hit (could be a child or ancestor of the target)
            ok = ht.desc.startsWith('<' + String(loc.tag || '').toLowerCase());
          } else {
            ok = true; // a failed probe does not block; fall back to the existing behavior
          }
        }
        if (!ok) continue; // covered -> try the next tier
      } else {
        hitPath = 'skipped';
      }
      alignUsed = i;
      done = true;
    }

    if (notInteractable) {
      // The target exists but is not actionable right now - this differs from "not found" and "covered" in how it is handled:
      // here we should wait for it to become usable or switch targets; retrying in place is pointless.
      recordMetrics(commandId, action, params, sessionId, targetTabId, tStart, {
        ok: false,
        cat: ErrorCategory.NOT_INTERACTABLE,
        alignUsed: null, alignsTried, hitPath,
        assertState: notInteractable.blockedBy || null,
        interceptor: firstInterceptor(interceptors),
        error: notInteractable.error,
      });
      sendCallbackSafe(commandId, failResult(
        (notInteractable.error || 'Element is not interactable') +
        ` — do NOT retry the same target immediately: wait for it to become ` +
        `usable (or pick a different target). blockedBy=${notInteractable.blockedBy || 'unknown'}`,
        ErrorCategory.NOT_INTERACTABLE,
      ), targetTabId, sessionId);
      return null;
    }

    if (!done) {
      const why = interceptors[0] || 'another element';
      const occluded = interceptors.some((d) => typeof d === 'string' && d.startsWith('<'));
      const cat = occluded ? ErrorCategory.OCCLUDED
        : interceptors.some((d) => String(d).startsWith('ambiguous:'))
          ? ErrorCategory.AMBIGUOUS
          : interceptors.some((d) => d === 'timeout')
            ? ErrorCategory.TIMEOUT
            : ErrorCategory.NOT_FOUND;
      recordMetrics(commandId, action, params, sessionId, targetTabId, tStart, {
        ok: false,
        cat,
        alignUsed: null, alignsTried, hitPath,
        interceptor: firstInterceptor(interceptors),
        error: why,
      });
      sendCallbackSafe(commandId, failResult(
        `Element does not receive pointer events at the ${action} point; ` +
        `${why} intercepts the ${action} (tried ${ALIGNMENTS.length} alignments: center/end/start). ` +
        `Try another selector or scope, or closeDialogs first.`,
        cat,
      ), targetTabId, sessionId);
      return null;
    }
  }

  if (loc && loc.ambiguous) {
    console.warn('[AgentSphere] degraded to first of', loc.ambiguous.matches, 'matches for', action);
  }

  try {
    await cdpAct(targetTabId, action, params, loc);
  } catch (e) {
    console.warn('[AgentSphere] cdpAct failed:', e?.message);
    recordMetrics(commandId, action, params, sessionId, targetTabId, tStart, {
      ok: false,
      cat: mapErrorCategory(e),
      alignUsed, alignsTried, hitPath,
      tag: loc && loc.tag, text: loc && loc.text,
      error: e.message,
    });
    sendCallbackSafe(commandId, failResult(action + ' failed: ' + e.message, mapErrorCategory(e)), targetTabId, sessionId);
    return null;
  }
  const data = { action, _executed: true };
  if (loc) {
    data.tag = loc.tag;
    data.text = loc.text;
  }
  if (action === 'type' && hasLocator(params)) {
    // Read back the input's current value so the model can check for accumulation/leftover text (_echo_ok means the target text was overwritten).
    try {
      const echo = await tabManager.askContent(targetTabId, {
        type: 'browser_operation',
        action: 'readInput',
        params: { selector: params.selector, text: params.text, ref: params.ref, scope: params.scope, occurrence: params.occurrence, frameId: params.frameId },
      }, 2, params.frameId, POST_ACTION_TIMEOUT_MS);
      const e = echo && echo.data;
      if (e && e.ok) {
        data._echo = e.value;
        data._echo_ok = (e.value || '') === (params.text || '');
      }
    } catch (err) {
      console.warn('[AgentSphere] readInput echo failed:', err?.message);
    }
  }
  const tabNow = await chrome.tabs.get(targetTabId).catch(() => null);
  data._url = (tabNow && tabNow.url) || '';

  // Write action post-check: after CDP dispatch, let things settle briefly (flyout/SPA expansion), then read the current state.
  // -> changed (whole-page fingerprint diff), _selfChanged (target element's own state diff), _hints, _clickable.
  try {
    await new Promise((r) => setTimeout(r, 300));
    const v = await tabManager.askContent(targetTabId, {
      type: 'browser_operation',
      action: 'verify',
      params: { _reuseToken: reuseToken, ref: loc ? loc.selfRef : params.ref },
    }, 2, params.frameId, POST_ACTION_TIMEOUT_MS);
    const vd = v && v.data;
    if (vd) {
      if (typeof vd.fp === 'string' && loc && typeof loc.preHash === 'string') {
        data.changed = loc.preHash !== vd.fp;
      }
      // _selfChanged answers "did the thing I clicked change". changed's whole-page fingerprint is blind to pure CSS expansion,
      // checkbox toggles and input value changes, which are exactly the most common success feedback - the two are complementary.
      if (typeof vd.selfFp === 'string' && vd.selfFp && loc && typeof loc.selfFp === 'string' && loc.selfFp) {
        data._selfChanged = loc.selfFp !== vd.selfFp;
      }
      if (vd._hints) data._hints = vd._hints;
      if (vd._url) data._url = vd._url;
    }
    if (loc && typeof loc.clickable === 'boolean') data._clickable = loc.clickable;
  } catch (err) {
    console.warn('[AgentSphere] post-action verify failed:', err?.message);
  }

  recordMetrics(commandId, action, params, sessionId, targetTabId, tStart, {
    ok: true,
    cat: null,
    alignUsed, alignsTried, hitPath,
    degraded: !!(loc && loc.ambiguous),
    matches: loc && loc.ambiguous ? loc.ambiguous.matches : null,
    retargeted: !!(loc && loc.retargeted),
    settled: loc && typeof loc.settled === 'boolean' ? loc.settled : null,
    tag: loc && loc.tag,
    text: loc && loc.text,
    changed: data.changed,
    selfChanged: data._selfChanged,
    echoOk: typeof data._echo_ok === 'boolean' ? data._echo_ok : null,
  });

  sendCallbackSafe(commandId, okResult(data, 'cdp'), targetTabId, sessionId);
  return data;
}

async function executeInPageInner(commandId, action, params, sessionId) {
  try {
    if (action === 'navigate') {
      await handleNavigate(commandId, params, sessionId);
      return;
    }

    // Non-navigate: bring the controlled tab to the foreground first, then dispatch. Background tabs are throttled by Chrome
    // (rAF paused, setTimeout clamped to >=1s, down to ~1/minute after 5 minutes hidden), while clickAt /
    // executeJS / screenshot / getContent and the write actions' stability waits all depend on render frames -
    // in the background every action slows down many times over and hits the backend action timeout. Placing it before all branches covers everything.
    // The target tab can only come from the isolated plugin window; when an explicit tabId points at a user tab, return NO_TAB directly.
    const resolvedTabId = await resolveTargetTabId(params);
    if (!resolvedTabId) {
      console.warn('[AgentSphere] no target tab in plugin window', {
        tabId: params.tabId,
        controlled: tabManager.getControlled(),
        pluginWindowId: tabManager.pluginWindowId,
        pluginGroupId: tabManager.pluginGroupId,
      });
      sendCallbackSafe(commandId, failResult('No target tab in the AgentSphere window', ErrorCategory.NO_TAB), null, sessionId);
      return;
    }
    await tabManager.ensureForeground(resolvedTabId);

    if (action === 'executeJS') {
      const targetTabId = resolvedTabId;
      if (!targetTabId) {
        sendCallbackSafe(commandId, failResult('No target tab', ErrorCategory.NO_TAB), null, sessionId);
        return;
      }
      const hostErr = await assertControlledHost(targetTabId);
      if (hostErr) {
        sendCallbackSafe(commandId, failResult(hostErr, ErrorCategory.WRONG_SITE), targetTabId, sessionId);
        return;
      }
      // params.timeout is an existing protocol field (ChromeExecuteContext.timeout + the offscreen whitelist);
      // it is only used to "shorten" here; the cap is always EXECJS_TIMEOUT_MS and never exceeds the backend's 30s.
      const execTimeout = params.timeout > 0
        ? Math.max(2000, Math.min(EXECJS_TIMEOUT_MS, params.timeout * 1000))
        : EXECJS_TIMEOUT_MS;
      const result = await executeJsOnTab(targetTabId, params.code, execTimeout);
      sendCallbackSafe(commandId, result, targetTabId, sessionId);
      return;
    }

    // getContent(mode:'axtree') - CDP accessibility tree (only used on explicit request, requires debugger attachment)
    if (action === 'getContent' && params.mode === 'axtree') {
      const targetTabId = resolvedTabId;
      if (!targetTabId) {
        sendCallbackSafe(commandId, failResult('No target tab', ErrorCategory.NO_TAB), null, sessionId);
        return;
      }
      const hostErr = await assertControlledHost(targetTabId);
      if (hostErr) {
        sendCallbackSafe(commandId, failResult(hostErr, ErrorCategory.WRONG_SITE), targetTabId, sessionId);
        return;
      }
      try {
        await cdpClient.attach(targetTabId);
        const tab = await chrome.tabs.get(targetTabId).catch(() => null);
const { nodes } = await cdpClient.sendCommand(targetTabId, 'Accessibility.getFullAXTree');
        // Role whitelist: browsers have far more accessibility roles than the original 15, and missing structural roles
        // (headings, images, list items, dialogs, etc.) make the whole page "invisible" from the AX perspective.
        const INTERACTIVE = new Set([
          'button', 'link', 'textbox', 'searchbox', 'combobox', 'checkbox', 'radio',
          'switch', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'tab', 'option',
          'listbox', 'slider', 'spinbutton', 'treeitem', 'gridcell',
          'heading', 'image', 'img', 'paragraph', 'listitem', 'cell', 'columnheader',
          'rowheader', 'row', 'rowgroup', 'grid', 'separator', 'toolbar', 'menu', 'menubar',
          'dialog', 'alertdialog', 'alert', 'status', 'tooltip', 'group', 'region',
          'form', 'search', 'article', 'banner', 'navigation', 'complementary',
          'contentinfo', 'main', 'definition', 'term', 'progressbar', 'meter',
          'tabpanel', 'tablist', 'tree', 'toolbarbutton',
        ]);
        const compact = [];
        let nameless = 0;
        for (const n of Array.isArray(nodes) ? nodes : []) {
          const role = n.role?.value;
          if (!role || !INTERACTIVE.has(role)) continue;
          const name = n.name?.value || '';
          // The accessible name (AXName) is computed per HTML-AAM: icon-only buttons are often empty yet still
          // interactive. The original implementation discarded them, losing exactly the icon-button class of targets.
          if (!name) nameless++;
          const state = (n.properties || [])
            .filter((p) => ['checked', 'disabled', 'expanded', 'selected', 'focused', 'required'].includes(p.name))
            .map((p) => `${p.name}:${p.value?.value ?? p.value?.type ?? true}`)
            .join(' ');
          // backendDOMNodeId links the AX node back to the real DOM node (the original implementation dropped it,
          // so observed elements could not be connected back to the DOM). This pass only outputs it; no action path is indexed.
          compact.push({
            role,
            name,
            backendDOMNodeId: n.backendDOMNodeId?.value ?? null,
            state,
          });
          if (compact.length >= 200) break;
        }
        sendCallbackSafe(commandId, {
          success: true,
          data: {
            _url: tab?.url || '',
            count: compact.length,
            nameless,
            _hint: nameless > 0
              ? `${nameless} node(s) have no accessible name (icon-only controls) — disambiguate by role + bounds, not name.`
              : '',
            nodes: compact,
          },
          method: 'axtree',
        }, targetTabId, sessionId);
      } catch (e) {
        sendCallbackSafe(commandId, failResult(e.message, ErrorCategory.UNKNOWN), targetTabId, sessionId);
      }
      return;
    }

    // writeAction entry: clickAt uses a raw coordinate click (CDP), alongside the other write actions
    if (action === 'clickAt') {
      const targetTabId = resolvedTabId;
      if (!targetTabId) {
        sendCallbackSafe(commandId, failResult('No target tab', ErrorCategory.NO_TAB), null, sessionId);
        return;
      }
      const hostErr = await assertControlledHost(targetTabId);
      if (hostErr) {
        sendCallbackSafe(commandId, failResult(hostErr, ErrorCategory.WRONG_SITE), targetTabId, sessionId);
        return;
      }
      await clickAtPoint(commandId, params, targetTabId, sessionId);
      return;
    }

    // screenshot: CDP capture -> report to backend -> callback carries fileKey (no oversized byte payloads in callbacks)
    if (action === 'screenshot') {
      const targetTabId = resolvedTabId;
      if (!targetTabId) {
        sendCallbackSafe(commandId, failResult('No target tab', ErrorCategory.NO_TAB), null, sessionId);
        return;
      }
      const hostErr = await assertControlledHost(targetTabId);
      if (hostErr) {
        sendCallbackSafe(commandId, failResult(hostErr, ErrorCategory.WRONG_SITE), targetTabId, sessionId);
        return;
      }
      try {
        const format = params.format === 'png' ? 'png' : 'jpeg';
        const quality = Number.isFinite(Number(params.quality))
          ? Math.max(1, Math.min(100, Math.round(Number(params.quality))))
          : 60;
        const fullPage = params.scope === 'full';
        const shot = await cdpClient.captureScreenshot(targetTabId, { format, quality, fullPage });
        const uploaded = await uploadScreenshotToBackend(shot.base64, shot.contentType);
        sendCallbackSafe(commandId, okResult({
          screenshot: {
            fileKey: uploaded.fileKey,
            contentType: shot.contentType,
            width: shot.width,
            height: shot.height,
            dpr: shot.dpr,
            viewportWidth: shot.viewportWidth,
            viewportHeight: shot.viewportHeight,
          },
        }, 'screenshot'), targetTabId, sessionId);
      } catch (e) {
        console.warn('[AgentSphere] screenshot failed:', e?.message);
        sendCallbackSafe(commandId, failResult('Screenshot failed: ' + e.message, mapErrorCategory(e)), targetTabId, sessionId);
      }
      return;
    }

    // Other actions → content script
    const targetTabId = resolvedTabId;
    if (!targetTabId) {
      sendCallbackSafe(commandId, failResult('No target tab', ErrorCategory.NO_TAB), null, sessionId);
      return;
    }
    const hostErr = await assertControlledHost(targetTabId);
    if (hostErr) {
      sendCallbackSafe(commandId, failResult(hostErr, ErrorCategory.WRONG_SITE), targetTabId, sessionId);
      return;
    }

    // Write actions: CDP trusted input (the only mutation path)
    if (WRITE_ACTIONS.has(action)) {
      await writeAction(commandId, action, params, targetTabId, sessionId);
      return;
    }

    // Page-level scrolling -> CDP mouseWheel (real wheel events + segmented pauses);
    // scrolling a specific element (selector/text/ref given) still uses the content script's scrollIntoView (that is a positioning action, not a human gesture)
    if (action === 'scroll' && !(params.selector || params.text || params.ref != null)) {
      await cdpClient.scrollPage(targetTabId, params.direction || 'down', params.amount);
      sendCallbackSafe(commandId, {
        success: true,
        action: 'scroll',
        data: { scrolled: params.direction || 'down', method: 'cdp-wheel' },
        detail: '',
      }, targetTabId, sessionId);
      return;
    }

    // Everything else -> content script (read-only / wait / upload / dialog etc.)
    const result = await tabManager.askContent(targetTabId, {
      type: 'browser_operation',
      action,
      params,
    }, 3, params.frameId);

    sendCallbackSafe(commandId, { ...result, action, detail: params.selector || params.url || '' }, targetTabId, sessionId);
  } catch (e) {
    console.error('[AgentSphere] executeInPage error:', e.message);
    sendCallbackSafe(commandId, failResult(e.message, mapErrorCategory(e)), tabManager.getControlled(), sessionId);
  }
}

/** Normalize a URL into a comparable form; ignoreHash is used for the display decision of "was it redirected". */
function comparableUrl(raw, ignoreHash) {
  try {
    const u = new URL(raw);
    if (ignoreHash) u.hash = '';
    return u.toString();
  } catch (e) {
    return String(raw || '');
  }
}

/** Whether two URLs point to the same address. Includes the hash by default - hash routes (such as #preview) are real navigation targets too. */
function sameUrl(a, b, ignoreHash = false) {
  if (!a || !b) return false;
  return comparableUrl(a, ignoreHash) === comparableUrl(b, ignoreHash);
}

/**
 * Read the page's real address. `chrome.tabs.url` may be stale during a redirect/navigation race and cannot be used
 * as evidence of "already reached the target page"; fall back to tabs.url when it cannot be read.
 */
async function resolveActualUrl(tabId, fallback) {
  const live = await tabManager.readPageUrl(tabId);
  return live || fallback || null;
}

/** Navigation wrap-up: report the page's real address uniformly and update the site guard/foreground/group. */
async function completeNavigate(commandId, sessionId, tabId, navT0, requestedUrl, extra = {}) {
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  const actualUrl = await resolveActualUrl(tabId, tab?.url || requestedUrl);
  const redirected = !sameUrl(actualUrl, requestedUrl, true);
  targetOrigin = originOf(actualUrl) || targetOrigin;
  await saveSessionState({ targetOrigin });
  tabManager.setControlled(tabId);
  await tabManager.ensureForeground(tabId);
  await tabManager.groupTab(tabId);
  const data = { tabId, url: actualUrl, redirected, ...extra };
  recordNavigate(commandId, sessionId, navT0, { ...data, ok: true });
  sendCallbackSafe(commandId, okResult(data, 'navigate'), tabId, sessionId);
}

/**
 * Perform one **real** navigation on an existing plugin tab.
 *
 * <p>The reuse branch must read the page's real location before deciding whether to navigate. The old implementation compared only
 * `chrome.tabs.url` and returned success immediately on a match - production thus saw "navigate succeeds instantly
 * while the page actually stays on the intercept page", and the model treated that false success as a recovery attempt.
 */
async function navigateExistingTab(commandId, sessionId, tabId, params, navT0, extra = {}) {
  // Focus before navigation: the page's own timers/lazy loading must not start out throttled.
  await tabManager.ensureForeground(tabId);
  let liveUrl = await tabManager.readPageUrl(tabId);
  if (liveUrl == null && await tabManager.ensureContentScript(tabId)) {
    liveUrl = await tabManager.readPageUrl(tabId);
  }
  if (liveUrl == null || !sameUrl(liveUrl, params.url)) {
    await chrome.tabs.update(tabId, { url: params.url });
    await tabManager.waitForTabComplete(tabId, 10000);
    await waitIdle(tabId);
  }
  if (!await tabManager.ensureContentScript(tabId)) {
    sendCallbackSafe(commandId, failResult('Content script unavailable after navigation', ErrorCategory.INJECT_FAILED), tabId, sessionId);
    return;
  }
  await completeNavigate(commandId, sessionId, tabId, navT0, params.url, extra);
}

async function handleNavigate(commandId, params, sessionId) {
  const navT0 = Date.now();
  tabManager.beginSession(sessionId);
  // If tabId specified, update that tab instead of creating a new one.
  // Only a tab already inside the AgentSphere window is eligible; a tabId that
  // points at the user's tab is ignored (we never navigate the user's tab).
  if (params.tabId && await tabManager.isPluginTab(params.tabId)) {
    try {
      await navigateExistingTab(commandId, sessionId, params.tabId, params, navT0);
      return;
    } catch (e) {
      console.log('[AgentSphere] Failed to update tab, falling back to create:', e.message);
    }
  }

  // Reuse an already-open tab showing this URL **inside the plugin window and this session**.
  // User tabs are never reused, and neither are tabs left behind by another session.
  const reusable = await tabManager.findReusableTab(params.url);
  if (reusable) {
    try {
      await navigateExistingTab(commandId, sessionId, reusable.id, params, navT0, { reused: true });
      return;
    } catch (e) {
      console.log('[AgentSphere] Reusable tab unusable, creating new one:', e.message);
    }
  }

  // Isolated plugin window: active:true only applies inside that window. The window is created
  // unfocused so it does not steal the user's current focus, then ensureForeground() below lifts
  // Chrome's hidden-tab throttling when needed (see TabManager background compatibility modes).
  const tab = await tabManager.openInPluginWindow(params.url);
  if (!tab?.id) {
    sendCallbackSafe(commandId, failResult('Failed to open tab in the AgentSphere window', ErrorCategory.NO_TAB), null, sessionId);
    return;
  }
  // Adopt first, then self-check: isPluginTab can self-heal via trustedTabId when window state is briefly missing.
  tabManager.setControlled(tab.id);
  if (!await tabManager.isPluginTab(tab.id)) {
    sendCallbackSafe(commandId, failResult('AgentSphere window state is not initialized; reload the extension', ErrorCategory.NO_TAB), tab.id, sessionId);
    return;
  }
  // Focus before waiting for load so the new page's scripts are not throttled while it initialises.
  await tabManager.ensureForeground(tab.id);
  await tabManager.waitForTabComplete(tab.id, 10000);
  await waitIdle(tab.id);
  if (!await tabManager.ensureContentScript(tab.id)) {
    sendCallbackSafe(commandId, failResult('Content script unavailable after navigation', ErrorCategory.INJECT_FAILED), tab.id, sessionId);
    return;
  }
  await completeNavigate(commandId, sessionId, tab.id, navT0, params.url);
}

function originOf(url) {
  try {
    return new URL(url).origin;
  } catch (e) {
    return null;
  }
}

// --- executeJS: two-tier execution, debugger only for strict-CSP sites ---
// NOTE: the ISOLATED world cannot eval at all — MV3's extension CSP is
// `script-src 'self' 'wasm-unsafe-eval' ...` which forbids eval()/new Function()
// (see Chrome docs "Content scripts → Content Security Policy"). So there is no
// isolated tier; we go straight to the MAIN world (page CSP applies) and fall
// back to chrome.debugger, which bypasses CSP entirely.
const cspBlockedOrigins = new Set(); // origins where scripting/MAIN eval is blocked by page CSP

function evalInWorldFunc() {
  // Runs inside chrome.scripting (MAIN world). Page CSP applies here.
  return async (src) => {
    let run;
    try {
      run = new Function('return (async () => { ' + src + '\n })()');
    } catch (e) {
      throw new Error('executeJS eval blocked by page CSP: ' + e.message);
    }
    try {
      const result = await run();
      // No return value: also read back document.title for side-effect verification (the document.title marker trick)
      // No need for another getContent round - __NO_RETURN__ means "executed successfully but returned nothing"; do not retry it as a failure.
      if (result === undefined) {
        return { __asVoid: true, __asTitle: typeof document !== 'undefined' ? document.title : '' };
      }
      return result;
    } catch (e) {
      throw new Error('executeJS runtime error: ' + (e && e.message ? e.message : e));
    }
  };
}

/** Add a deadline to any promise. The timer must be cleared on both paths, otherwise timers pile up. */
function withDeadline(promise, ms, label) {
  let timer;
  return Promise.race([
    Promise.resolve(promise).finally(() => clearTimeout(timer)),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(label + ' timed out after ' + ms + 'ms')), ms);
    }),
  ]);
}

async function runScripting(tabId, code) {
  const results = await chrome.scripting.executeScript({
    target: { tabId },
    world: 'MAIN',
    func: evalInWorldFunc(),
    args: [code],
  });
  const value = results?.[0]?.result;
  if (value && typeof value === 'object' && value.__asVoid === true) {
    const r = okWarning(
      'scripting-main',
      'code executed but did not return a value (__NO_RETURN__ is expected); if side effects were flagged via document.title, verify with _documentTitle',
      '__NO_RETURN__',
      'void',
    );
    r._documentTitle = value.__asTitle;
    return r;
  }
  if (value === undefined) {
    return okWarning('scripting-main', 'code did not return a value; may not have executed', '__NO_RETURN__');
  }
  return okResult(value, 'scripting-main');
}

async function getTabOrigin(tabId) {
  try {
    const tab = await chrome.tabs.get(tabId);
    if (!tab?.url) return null;
    return new URL(tab.url).origin;
  } catch (e) {
    return null;
  }
}

async function executeJsOnTab(tabId, code, timeoutMs) {
  // Tier 1: MAIN world via chrome.scripting — page CSP applies; works on most sites.
  const origin = await getTabOrigin(tabId);
  if (!origin || !cspBlockedOrigins.has(origin)) {
    try {
      return await withDeadline(runScripting(tabId, code), timeoutMs, 'executeJS (MAIN world)');
    } catch (e) {
      if (origin) cspBlockedOrigins.add(origin);
    }
  }
  // Tier 2: chrome.debugger Runtime.evaluate — bypasses CSP entirely; strict sites land here.
  try {
    // The CDP layer needs the deadline too: this path enters cdp-client's serial queue,
    // and without a bound it would poison the whole queue as it did before (subsequent click/insertText all queue up and never run).
    return await cdpClient.evaluate(tabId, code, timeoutMs);
  } catch (e4) {
    const msg = String(e4.message);
    const isTimeout = msg.includes('timed out');
    return failResult(
      e4.message,
      isTimeout ? ErrorCategory.TIMEOUT
        : msg.includes('already attached') ? ErrorCategory.DETACHED : ErrorCategory.CSP_BLOCKED,
      { method: 'debugger' },
    );
  }
}

// --- Multi-device: after connecting, ask the server whether several are online and show a selection overlay in the page when needed ---
let connectionsCheckedAt = 0;

async function syncConnections() {
  if (!token || !baseUrl) return;
  if (Date.now() - connectionsCheckedAt < 10000) return;
  connectionsCheckedAt = Date.now();
  try {
    const res = await fetch(`${baseUrl}/api/v1/runtime/user/connections`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        ...(deviceId ? { 'X-Device-Id': deviceId } : {}),
      },
    });
    if (!res.ok) return;
    const body = await res.json();
    const devices = Array.isArray(body?.devices) ? body.devices : [];
    if (devices.length <= 1) return;
    const preferred = body.preferredDeviceId || '';
    if (preferred && preferred === deviceId) return;
    chrome.storage.local.set({ deviceChoices: devices, preferredDeviceId: preferred }).catch(() => {});
    broadcastToHostTabs({
      type: 'showDeviceChooser',
      devices,
      preferredDeviceId: preferred,
      currentDeviceId: deviceId,
    });
  } catch (e) {
    // Ignore network jitter; retry on the next connection state change
  }
}

function broadcastToHostTabs(message) {
  chrome.tabs.query({}, (tabs) => {
    for (const tab of tabs) {
      if (tab.id == null) continue;
      if (!shouldInject(tab.url || '')) continue;
      chrome.tabs.sendMessage(tab.id, message).catch(() => {});
    }
  });
}

// --- Report result back to backend ---
function sendCallbackSafe(commandId, result, captureTabId, sessionId) {
  sendCallback(commandId, result, sessionId).catch((e) => {
    console.warn('[AgentSphere] sendCallback rejected:', e.message);
  });
}

async function sendCallback(commandId, result, sessionId) {
  try {
    const meta = commandMeta.get(commandId) || {};
    commandMeta.delete(commandId);
    await fetch(`${baseUrl}/api/v1/chrome/callback?sessionId=${sessionId}`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        commandId,
        ...result,
        deviceId: deviceId || null,
        deviceName: deviceName || null,
        source: tokenSource || null,
        extensionVersion: EXTENSION_VERSION,
        runId: meta.runId ?? null,
        callId: meta.callId ?? null,
      }),
    });
    chrome.storage.local.get(['logs'], (data) => {
      const logs = data.logs || [];
      logs.push({ time: Date.now(), action: result?.action || 'callback', success: !!result?.success, detail: result?.error || '' });
      if (logs.length > 200) logs.splice(0, logs.length - 200);
      chrome.storage.local.set({ logs }).catch(() => {});
    });
  } catch (e) {
    console.error('[AgentSphere] Failed to send callback:', e);
  }
}

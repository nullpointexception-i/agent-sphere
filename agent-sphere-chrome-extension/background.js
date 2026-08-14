/**
 * Service Worker (MV3, ESM) — message router, command dispatch, tab grouping,
 * offscreen keepalive, and callback reporting to the backend.
 *
 * Long-lived task SSE lives in the offscreen document; this worker relays
 * browser_operation commands into tabs and reports results via HTTP callback.
 */
import { cdpClient } from './lib/cdp-client.js';
import { tabManager } from './lib/tab-manager.js';
import { okResult, failResult, ErrorCategory } from './lib/result.js';
import { ensureOffscreenDocument, askOffscreen } from './lib/offscreen-bridge.js';

console.log('[AgentSphere] Service Worker started', new Date().toISOString());

let token = '';
let baseUrl = '';

// --- Keep the offscreen document (SSE host) alive / recreate if the browser closed it ---
function startOffscreenKeepalive() {
  chrome.alarms.create('offscreen-keepalive', { periodInMinutes: 1 });
  chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === 'offscreen-keepalive') {
      ensureOffscreenDocument().then((ok) => {
        if (!ok) console.warn('[AgentSphere] Offscreen document unavailable');
      });
    }
  });
}
startOffscreenKeepalive();

function startSseHost() {
  chrome.storage.local.get(['token', 'baseUrl'], (data) => {
    if (data.token && data.baseUrl) {
      token = data.token;
      baseUrl = data.baseUrl;
    }
    ensureOffscreenDocument();
    // Self-heal after SW restart: re-inject into already-open matching tabs
    loadConfiguredUrls(() => injectMatchingTabs());
  });
}
startSseHost();

// --- Configured URLs (popup Settings): widget hosts (frontendUrls[]) + main site (mainUrl) ---
let frontendUrls = [];
let mainUrl = '';

function loadConfiguredUrls(done) {
  chrome.storage.local.get(['settings'], (data) => {
    const s = data.settings || {};
    frontendUrls = Array.isArray(s.frontendUrls)
      ? s.frontendUrls.filter(Boolean)
      : s.frontendUrl
        ? [s.frontendUrl]
        : [];
    mainUrl = s.mainUrl || '';
    console.log('[AgentSphere] Config:', JSON.stringify({ frontendUrls, mainUrl }));
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
    console.log('[AgentSphere] Settings changed:', JSON.stringify({ frontendUrls, mainUrl }));
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
    const { commandId, action, params, sessionId } = msg.cmd;
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
      sendResponse({ token: t, baseUrl: b });
    })();
    return true; // async response
  }

  // offscreen → background: report task connection status (popup badge)
  if (msg && msg.target === 'background' && msg.action === 'task:status') {
    chrome.storage.local.set({ taskConnected: !!msg.connected }).catch(() => {});
    return false;
  }

  // content script → background: auth
  if ((msg.type === 'auth' || msg.type === 'auth_token') && msg.token) {
    token = msg.token;
    baseUrl = msg.baseUrl || baseUrl;
    chrome.storage.local.set({
      token,
      baseUrl,
      displayName: msg.displayName || '',
    }).catch(() => {});
    console.log('[AgentSphere] auth: task connection established (user-level)');
    fetchSsoDisplayName();
    askOffscreen({ action: 'task:init' });
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

// --- SSO 展示名：providerCode@subject（如 bole@elvin），按 token 去重 ---
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
async function getActiveTabId() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab?.id;
}

async function waitIdle(tabId) {
  await chrome.scripting.executeScript({
    target: { tabId },
    func: () => new Promise((r) => requestIdleCallback(r, { timeout: 5000 })),
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
  try {
    if (action === 'navigate') {
      await handleNavigate(commandId, params, sessionId);
      return;
    }

    if (action === 'executeJS') {
      const targetTabId = params.tabId || tabManager.getControlled() || (await getActiveTabId());
      if (!targetTabId) {
        sendCallbackSafe(commandId, failResult('No target tab', ErrorCategory.NO_TAB), null, sessionId);
        return;
      }
      const result = await executeJsOnTab(targetTabId, params.code);
      sendCallbackSafe(commandId, result, targetTabId, sessionId);
      return;
    }

    // Other actions → content script
    const targetTabId = params.tabId || tabManager.getControlled() || (await getActiveTabId());
    if (!targetTabId) {
      sendCallbackSafe(commandId, failResult('No target tab', ErrorCategory.NO_TAB), null, sessionId);
      return;
    }

    const result = await tabManager.askContent(targetTabId, {
      type: 'browser_operation',
      action,
      params,
    });

    // Form submit button detected → navigate to extracted URL directly
    if (result?.data?._submitUrl) {
      executeInPage(commandId, 'navigate', { url: result.data._submitUrl }, sessionId);
      return;
    }

    // New tab auto-follow: click opened a new tab → switch to it
    if (action === 'click' && result?.data?._newTabExpected) {
      await new Promise((resolve) => {
        if (tabManager.tabFollowPending) return resolve();
        tabManager.tabFollowResolve = resolve;
        setTimeout(() => { tabManager.tabFollowResolve = null; resolve(); }, 5000);
      });
      if (tabManager.tabFollowPending) {
        await tabManager.waitForTabComplete(tabManager.tabFollowPending.newTabId, 10000);
        await waitIdle(tabManager.tabFollowPending.newTabId);
        const finalTab = await chrome.tabs.get(tabManager.tabFollowPending.newTabId).catch(() => null);
        if (finalTab) tabManager.tabFollowPending.url = finalTab.url || tabManager.tabFollowPending.url;
        result.data._newTabId = tabManager.tabFollowPending.newTabId;
        result.data._newTabUrl = tabManager.tabFollowPending.url;
        tabManager.groupTab(tabManager.tabFollowPending.newTabId);
        sendCallbackSafe(commandId, { ...result, action, detail: params.selector || '' }, tabManager.tabFollowPending.newTabId, sessionId);
        tabManager.tabFollowPending = null;
        return;
      }
    }

    sendCallbackSafe(commandId, { ...result, action, detail: params.selector || params.url || '' }, targetTabId, sessionId);
  } catch (e) {
    console.error('[AgentSphere] executeInPage error:', e.message);
    sendCallbackSafe(commandId, failResult(e.message, mapErrorCategory(e)), tabManager.getControlled(), sessionId);
  }
}

async function handleNavigate(commandId, params, sessionId) {
  // If tabId specified, update that tab instead of creating a new one
  if (params.tabId) {
    try {
      const tab = await chrome.tabs.update(params.tabId, { url: params.url });
      await tabManager.injectContentScript(tab.id);
      await tabManager.waitForTabComplete(tab.id);
      await waitIdle(tab.id);
      const finalTab = await chrome.tabs.get(tab.id).catch(() => tab);
      const finalUrl = finalTab.url || params.url;
      tabManager.setControlled(tab.id);
      tabManager.groupTab(tab.id);
      sendCallbackSafe(commandId, okResult({ tabId: tab.id, url: finalUrl, redirected: finalUrl !== params.url }, 'navigate'), tab.id, sessionId);
      return;
    } catch (e) {
      console.log('[AgentSphere] Failed to update tab, falling back to create:', e.message);
    }
  }

  // Reuse existing tab if same URL is already open
  if (tabManager.getControlled()) {
    try {
      const existingTab = await chrome.tabs.get(tabManager.getControlled());
      if (existingTab?.url === params.url || existingTab?.pendingUrl === params.url) {
        const existingUrl = existingTab.url || params.url;
        sendCallbackSafe(commandId, okResult({ tabId: existingTab.id, url: existingUrl, redirected: existingUrl !== params.url }, 'navigate'), existingTab.id, sessionId);
        return;
      }
    } catch (e) {
      // Tab no longer exists, create a new one
      console.log('[AgentSphere] Controlled tab gone, creating new one');
    }
  }

  const tab = await chrome.tabs.create({ url: params.url, active: false });
  await tabManager.injectContentScript(tab.id);
  await tabManager.waitForTabComplete(tab.id);
  await waitIdle(tab.id);
  const finalTab = await chrome.tabs.get(tab.id).catch(() => tab);
  const finalUrl = finalTab.url || params.url;
  tabManager.setControlled(tab.id);
  tabManager.groupTab(tab.id);
  sendCallbackSafe(commandId, okResult({ tabId: tab.id, url: finalUrl, redirected: finalUrl !== params.url }, 'navigate'), tab.id, sessionId);
}

// --- executeJS: tiered execution, debugger only as the last resort ---
const cspBlockedOrigins = new Set(); // origins where scripting/MAIN injection is blocked

function evalInWorldFunc() {
  // Runs inside chrome.scripting (ISOLATED or MAIN world).
  return async (src) => {
    const run = new Function('return (async () => { ' + src + '\n })()');
    const result = await run();
    return result === undefined ? '__NO_RETURN__' : result;
  };
}

async function runIsolatedViaScripting(tabId, code) {
  const results = await chrome.scripting.executeScript({
    target: { tabId },
    world: 'ISOLATED',
    func: evalInWorldFunc(),
    args: [code],
  });
  const value = results?.[0]?.result;
  return {
    success: true,
    data: value,
    method: 'isolated',
    _resultType: value === '__NO_RETURN__' ? 'void' : typeof value,
  };
}

async function runMainViaScripting(tabId, code) {
  const results = await chrome.scripting.executeScript({
    target: { tabId },
    world: 'MAIN',
    func: evalInWorldFunc(),
    args: [code],
  });
  const value = results?.[0]?.result;
  return {
    success: true,
    data: value,
    method: 'scripting-main',
    _resultType: value === '__NO_RETURN__' ? 'void' : typeof value,
  };
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

async function executeJsOnTab(tabId, code) {
  // Tier 1: isolated world eval (chrome.scripting) — never CSP-blocked, no page globals.
  try {
    return await runIsolatedViaScripting(tabId, code);
  } catch (e1) {
    // Tier 2a: MAIN world via inject.js bridge (content script, Ui.Vision pattern).
    try {
      const res = await tabManager.askContent(tabId, {
        type: 'browser_operation',
        action: 'executeJS',
        params: { code },
      });
      if (res?.success) return res;
      throw new Error(res?.error || 'inject bridge failed');
    } catch (e2) {
      // Tier 2b: MAIN world via chrome.scripting (unless this origin already proved CSP-strict).
      const origin = await getTabOrigin(tabId);
      if (origin && !cspBlockedOrigins.has(origin)) {
        try {
          return await runMainViaScripting(tabId, code);
        } catch (e3) {
          if (origin) cspBlockedOrigins.add(origin);
        }
      }
      // Tier 3: chrome.debugger Runtime.evaluate — bypasses CSP entirely.
      try {
        return await cdpClient.evaluate(tabId, code);
      } catch (e4) {
        const msg = String(e4.message);
        return failResult(
          e4.message,
          msg.includes('already attached') ? ErrorCategory.DETACHED : ErrorCategory.CSP_BLOCKED,
          { method: 'debugger' },
        );
      }
    }
  }
}

// --- Report result back to backend ---
function sendCallbackSafe(commandId, result, captureTabId, sessionId) {
  sendCallback(commandId, result, sessionId).catch((e) => {
    console.warn('[AgentSphere] sendCallback rejected:', e.message);
  });
}

async function sendCallback(commandId, result, sessionId) {
  try {
    await fetch(`${baseUrl}/api/v1/chrome/callback?sessionId=${sessionId}`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ commandId, ...result }),
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

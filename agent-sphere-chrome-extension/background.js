/**
 * Service Worker (MV3, ESM) — message router, command dispatch, tab grouping,
 * offscreen keepalive, and callback reporting to the backend.
 *
 * Long-lived task SSE lives in the offscreen document; this worker relays
 * browser_operation commands into tabs and reports results via HTTP callback.
 */
import { cdpClient } from './lib/cdp-client.js';
import { tabManager } from './lib/tab-manager.js';
import { okResult, okWarning, failResult, ErrorCategory } from './lib/result.js';
import { ensureOffscreenDocument, askOffscreen } from './lib/offscreen-bridge.js';

console.log('[AgentSphere] Service Worker started', new Date().toISOString());

let token = '';
let baseUrl = '';
// 最近一次 navigate 的目标 origin：受控 tab 若离开该站点则拒绝继续操作（防跑偏）。
let targetOrigin = null;
// 命令并发守卫：导航重建 content script 期间悬空的命令不再堆积，避免后端 10s 空等。
// 用时间戳租约：单命令超时（45s）后自动释放，防一条卡死命令锁死整个 session。
const COMMAND_LEASE_MS = 45000;
let commandInFlightAt = 0;

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
    // 先确保 offscreen 文档存在再下发连接指令，避免 MV3 空闲关闭导致 task:init 丢失、
    // 只能等 30s/1min 恢复
    ensureOffscreenDocument().then(() => {
      askOffscreen({ action: 'task:init' });
    });
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
  if (commandInFlightAt && Date.now() - commandInFlightAt < COMMAND_LEASE_MS) {
    sendCallbackSafe(
      commandId,
      failResult('上一命令仍在执行（并发守卫），请稍候重试', ErrorCategory.UNKNOWN),
      tabManager.getControlled(),
      sessionId,
    );
    return;
  }
  if (commandInFlightAt) {
    console.warn('[AgentSphere] command lease expired, releasing stuck guard');
  }
  commandInFlightAt = Date.now();
  const t0 = Date.now();
  try {
    await executeInPageInner(commandId, action, params, sessionId);
  } finally {
    commandInFlightAt = 0;
    console.log(`[ChromeCmd] action=${action} tab=${params.tabId || tabManager.getControlled() || '-'} ms=${Date.now() - t0}`);
  }
}

// 受控 tab 必须仍在目标站点内，否则拒绝操作（run 26 曾跑偏到 localhost:8001）。
async function assertControlledHost(tabId) {
  if (!targetOrigin) return null;
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  if (!tab || !tab.url) return null;
  try {
    const host = new URL(tab.url).host;
    if (host !== new URL(targetOrigin).host) {
      return `受控 tab 已离开目标站点（实际 ${tab.url}，目标 ${targetOrigin}）。请先 navigate 回主站点再继续，勿在错误页面操作。`;
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

// 写动作统一走 CDP 受信输入：内容脚本先跨帧定位算出主视口坐标，
// 再由 Input.dispatchMouseEvent / Input.insertText / Input.dispatchKeyEvent 派发（isTrusted=true）。
const WRITE_ACTIONS = new Set(['click', 'type', 'key', 'hover']);

function hasLocator(params) {
  return !!(params && (params.selector || params.text || params.ref != null));
}

async function locatePoint(tabId, params) {
  const loc = await tabManager.askContent(tabId, {
    type: 'browser_operation',
    action: 'locate',
    params,
  }, 2, params && params.frameId);
  const d = loc && loc.data;
  if (!d) return null;
  if (d.ok === false) return { ok: false, error: d.error, matches: d.matches, suggested: d.suggested };
  if (!d.point || typeof d.point.x !== 'number' || typeof d.point.y !== 'number') return null;
  return { ok: true, point: d.point, tag: d.tag, text: d.text, count: d.count };
}

async function cdpAct(tabId, action, params, loc) {
  switch (action) {
    case 'click':
      await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y);
      break;
    case 'hover':
      await cdpClient.nativeHover(tabId, loc.point.x, loc.point.y);
      break;
    case 'type':
      await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y); // 先受信聚焦
      if (params.append !== true) await cdpClient.clearInput(tabId); // insertText 只追加，整字段替换前先清空
      await cdpClient.insertText(tabId, params.text || '');
      if (params.submit) await cdpClient.nativeKeyPress(tabId, 'Enter', 'Enter', 13);
      break;
    case 'key': {
      if (loc) await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y); // 可选聚焦到给定元素
      const ki = keyInfoOf(params.key);
      await cdpClient.nativeKeyPress(tabId, ki.key, ki.code, ki.keyCode);
      break;
    }
  }
}

// 写动作入口：locate → CDP；未命中/歧义/异常即报错，不降级不重试。
async function writeAction(commandId, action, params, targetTabId, sessionId) {
  let loc = null;
  if (hasLocator(params)) {
    loc = await locatePoint(targetTabId, params);
    if (!loc || loc.ok === false) {
      if (loc && loc.error === 'ambiguous') {
        const scopeHint = (loc.suggested && loc.suggested.length)
          ? '；建议容器 scope：' + loc.suggested.join(' / ')
          : '；加 index/occurrence 或 scope';
        sendCallbackSafe(commandId, failResult(
          `Ambiguous: ${loc.matches || 2} on-screen matches for ${action}${scopeHint}`,
          ErrorCategory.AMBIGUOUS,
        ), targetTabId, sessionId);
      } else {
        const what = params.ref != null ? 'ref ' + params.ref : (params.selector || params.text);
        sendCallbackSafe(commandId, failResult('Element not found: ' + what, ErrorCategory.NOT_FOUND), targetTabId, sessionId);
      }
      return null;
    }
  } else if (action !== 'key') {
    sendCallbackSafe(commandId, failResult(action + ' requires selector/text/ref', ErrorCategory.NOT_FOUND), targetTabId, sessionId);
    return null;
  }
  try {
    await cdpAct(targetTabId, action, params, loc);
  } catch (e) {
    console.warn('[AgentSphere] cdpAct failed:', e?.message);
    sendCallbackSafe(commandId, failResult(action + ' failed: ' + e.message, mapErrorCategory(e)), targetTabId, sessionId);
    return null;
  }
  const data = { action, _executed: true };
  if (loc) {
    data.tag = loc.tag;
    data.text = loc.text;
  }
  if (action === 'type' && hasLocator(params)) {
    // 回读输入框当前值，供模型核对是否被累加/残留旧词（_echo_ok 表示已覆盖目标文本）。
    try {
      const echo = await tabManager.askContent(targetTabId, {
        type: 'browser_operation',
        action: 'readInput',
        params: { selector: params.selector, text: params.text, ref: params.ref, scope: params.scope, occurrence: params.occurrence, frameId: params.frameId },
      }, 2, params.frameId);
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
  sendCallbackSafe(commandId, okResult(data, 'cdp'), targetTabId, sessionId);
  return data;
}

async function executeInPageInner(commandId, action, params, sessionId) {
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
      const hostErr = await assertControlledHost(targetTabId);
      if (hostErr) {
        sendCallbackSafe(commandId, failResult(hostErr, ErrorCategory.WRONG_SITE), targetTabId, sessionId);
        return;
      }
      const result = await executeJsOnTab(targetTabId, params.code);
      sendCallbackSafe(commandId, result, targetTabId, sessionId);
      return;
    }

    // getContent(mode:'axtree') — CDP 可访问性树（仅显式请求时使用，需 debugger 附着）
    if (action === 'getContent' && params.mode === 'axtree') {
      const targetTabId = params.tabId || tabManager.getControlled() || (await getActiveTabId());
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
        const INTERACTIVE = new Set([
          'button', 'link', 'textbox', 'searchbox', 'combobox', 'checkbox', 'radio',
          'switch', 'menuitem', 'tab', 'option', 'listbox', 'slider', 'spinbutton', 'treeitem',
        ]);
        const compact = [];
        for (const n of Array.isArray(nodes) ? nodes : []) {
          const role = n.role?.value;
          if (!role || !INTERACTIVE.has(role)) continue;
          const name = n.name?.value || '';
          if (!name) continue;
          const state = (n.properties || [])
            .filter((p) => ['checked', 'disabled', 'expanded', 'selected', 'focused'].includes(p.name))
            .map((p) => `${p.name}:${p.value?.value ?? p.value?.type ?? true}`)
            .join(' ');
          compact.push({ role, name, state });
          if (compact.length >= 200) break;
        }
        sendCallbackSafe(commandId, {
          success: true,
          data: { _url: tab?.url || '', count: compact.length, nodes: compact },
          method: 'axtree',
        }, targetTabId, sessionId);
      } catch (e) {
        sendCallbackSafe(commandId, failResult(e.message, ErrorCategory.UNKNOWN), targetTabId, sessionId);
      }
      return;
    }

    // Other actions → content script
    const targetTabId = params.tabId || tabManager.getControlled() || (await getActiveTabId());
    if (!targetTabId) {
      sendCallbackSafe(commandId, failResult('No target tab', ErrorCategory.NO_TAB), null, sessionId);
      return;
    }
    const hostErr = await assertControlledHost(targetTabId);
    if (hostErr) {
      sendCallbackSafe(commandId, failResult(hostErr, ErrorCategory.WRONG_SITE), targetTabId, sessionId);
      return;
    }

    // 写动作：CDP 受信输入（唯一变更路径）
    if (WRITE_ACTIONS.has(action)) {
      await writeAction(commandId, action, params, targetTabId, sessionId);
      return;
    }

    // 其余动作 → 内容脚本（只读 / wait / scroll / upload / dialog 等）
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

async function handleNavigate(commandId, params, sessionId) {
  // If tabId specified, update that tab instead of creating a new one
  if (params.tabId) {
    try {
      const tab = await chrome.tabs.update(params.tabId, { url: params.url });
      await tabManager.waitForTabComplete(tab.id, 10000);
      await waitIdle(tab.id);
      if (!await tabManager.ensureContentScript(tab.id)) {
        sendCallbackSafe(commandId, failResult('Content script unavailable after navigation', ErrorCategory.INJECT_FAILED), tab.id, sessionId);
        return;
      }
      const finalTab = await chrome.tabs.get(tab.id).catch(() => tab);
      const finalUrl = finalTab.url || params.url;
      targetOrigin = originOf(finalUrl) || targetOrigin;
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
        if (!await tabManager.ensureContentScript(existingTab.id)) {
          sendCallbackSafe(commandId, failResult('Content script unavailable in existing tab', ErrorCategory.INJECT_FAILED), existingTab.id, sessionId);
          return;
        }
        const existingUrl = existingTab.url || params.url;
        targetOrigin = originOf(existingUrl) || targetOrigin;
        sendCallbackSafe(commandId, okResult({ tabId: existingTab.id, url: existingUrl, redirected: existingUrl !== params.url }, 'navigate'), existingTab.id, sessionId);
        return;
      }
    } catch (e) {
      // Tab no longer exists, create a new one
      console.log('[AgentSphere] Controlled tab gone, creating new one');
    }
  }

  const tab = await chrome.tabs.create({ url: params.url, active: false });
  await tabManager.waitForTabComplete(tab.id, 10000);
  await waitIdle(tab.id);
  if (!await tabManager.ensureContentScript(tab.id)) {
    sendCallbackSafe(commandId, failResult('Content script unavailable after navigation', ErrorCategory.INJECT_FAILED), tab.id, sessionId);
    return;
  }
  const finalTab = await chrome.tabs.get(tab.id).catch(() => tab);
  const finalUrl = finalTab.url || params.url;
  targetOrigin = originOf(finalUrl) || targetOrigin;
  tabManager.setControlled(tab.id);
  tabManager.groupTab(tab.id);
  sendCallbackSafe(commandId, okResult({ tabId: tab.id, url: finalUrl, redirected: finalUrl !== params.url }, 'navigate'), tab.id, sessionId);
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
      return result === undefined ? '__NO_RETURN__' : result;
    } catch (e) {
      throw new Error('executeJS runtime error: ' + (e && e.message ? e.message : e));
    }
  };
}

async function runScripting(tabId, code) {
  const results = await chrome.scripting.executeScript({
    target: { tabId },
    world: 'MAIN',
    func: evalInWorldFunc(),
    args: [code],
  });
  const value = results?.[0]?.result;
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

async function executeJsOnTab(tabId, code) {
  // Tier 1: MAIN world via chrome.scripting — page CSP applies; works on most sites.
  const origin = await getTabOrigin(tabId);
  if (!origin || !cspBlockedOrigins.has(origin)) {
    try {
      return await runScripting(tabId, code);
    } catch (e) {
      if (origin) cspBlockedOrigins.add(origin);
    }
  }
  // Tier 2: chrome.debugger Runtime.evaluate — bypasses CSP entirely; strict sites land here.
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

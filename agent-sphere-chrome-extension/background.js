/**
 * Service Worker - Maintains SSE connection, routes commands to content script.
 */
console.log('[AgentSphere] Service Worker started', new Date().toISOString());
let token = '';
let baseUrl = '';

// --- Keep SW alive & zombie-connection detection: prefer chrome.alarms (can wake a suspended SW);
// fall back to setInterval when alarms are unavailable (e.g. legacy registration without the permission) ---
let keepaliveTimer = null;

function checkConnection() {
  // 无凭证时不动作（扩展刚加载、认证尚未从页面拉取），避免误导性重连日志
  if (!token || !baseUrl) return;
  // 1) 未连接 → 直接建立用户级 task 流（SW 重启 / 流关闭后的兜底）
  if (!taskAbortController || taskAbortController.signal.aborted) {
    console.log('[AgentSphere] Keepalive: reconnecting task SSE');
    connectTaskSSE();
    return;
  }
  // 2) Zombie-connection detection: backend heartbeats every 15s; no data for too long → treat as stalled, reconnect
  if (lastDataAt && Date.now() - lastDataAt > ZOMBIE_STALE_MS) {
    console.warn('[AgentSphere] Keepalive: no task SSE data for a while, reconnecting');
    taskAbortController.abort();
    taskAbortController = null;
    connectTaskSSE();
  }
}

function startKeepalive() {
  if (chrome.alarms && typeof chrome.alarms.create === 'function') {
    try {
      chrome.alarms.create('sse-keepalive', { periodInMinutes: 1 });
      chrome.alarms.onAlarm.addListener((alarm) => {
        if (alarm.name === 'sse-keepalive') checkConnection();
      });
      return;
    } catch (e) {
      console.warn('[AgentSphere] chrome.alarms unavailable, using setInterval', e);
    }
  }
  keepaliveTimer = setInterval(checkConnection, 30000);
}
startKeepalive();

// --- Start SSE on startup: 只建立用户级 task 连接（不依赖 session） ---
function startSSE() {
  chrome.storage.local.get(['token', 'baseUrl'], (data) => {
    if (data.token && data.baseUrl) {
      token = data.token;
      baseUrl = data.baseUrl;
      ensureTaskSSE();
    }
    // Self-heal after SW restart / extension reload: once config is ready, re-inject into matching tabs
    loadConfiguredUrls(() => injectMatchingTabs());
  });
}

startSSE();

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
    // Re-inject into already-open matching tabs on startup (e.g. right after reload)
    injectMatchingTabs();
    if (typeof done === 'function') done();
  });
}

// --- Proactively query the matching tab for a session (self-heal after SW restart / reload) ---
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
    // After first saving default config, immediately re-inject into matching tabs
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
    let count = 0;
    for (const tab of tabs) {
      if (tab.id != null && shouldInject(tab.url || '')) {
        injectContentScript(tab.id);
        count++;
      }
    }
    console.log('[AgentSphere] Injected into', count, 'matching tab(s)');
  });
}

// --- Detect session changes from tab URL (works even without content script) ---
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (!changeInfo.url) return;
  const url = changeInfo.url;
  // Inject into: main-site chat page or configured frontend sites (widget hosts)
  if (shouldInject(url)) injectContentScript(tabId);
});

// --- Detect SPA route changes (pushState/replaceState) for injection ---
chrome.webNavigation.onHistoryStateUpdated.addListener((details) => {
  if (!details.url) return;
  if (shouldInject(details.url)) injectContentScript(details.tabId);
});

// --- Listen for auth info from content script ---
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  // 登录即连：仅需用户 token 即可建立用户级 task 流（不依赖 session）
  if ((msg.type === 'auth' || msg.type === 'auth_token') && msg.token) {
    token = msg.token;
    baseUrl = msg.baseUrl || baseUrl;
    chrome.storage.local.set({
      token,
      baseUrl,
      displayName: msg.displayName || '',
    }).catch(() => {});
    console.log('[AgentSphere] auth: establishing task connection (user-level)');
    ensureTaskSSE();
    fetchSsoDisplayName();
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
  // content.js delegates executeJS → uses chrome.debugger.Runtime.evaluate (bypasses CSP)
  if (msg.type === 'execute_js') {
    (async () => {
      try {
        const targetTabId = msg.tabId || controlledTabId || (await getActiveTabId());
        if (!targetTabId) { sendResponse({ success: false, error: 'No target tab' }); return; }
        if (attachedTabId !== targetTabId) {
          if (attachedTabId) await chrome.debugger.detach({ tabId: attachedTabId }).catch(() => {});
          await chrome.debugger.attach({ tabId: targetTabId }, "1.3");
          attachedTabId = targetTabId;
        }
        const { result: evalResult } = await chrome.debugger.sendCommand(
          { tabId: targetTabId },
          "Runtime.evaluate",
          { expression: msg.code, returnByValue: true }
        );
        const rawValue = evalResult?.value;
        sendResponse({
          success: !evalResult?.exceptionDetails,
          data: rawValue !== undefined ? rawValue : '__NO_RETURN__',
          _resultType: rawValue === undefined ? 'void' : typeof rawValue,
          error: evalResult?.exceptionDetails?.text || null,
        });
      } catch (e) {
        sendResponse({ success: false, error: e.message });
      }
    })();
    return true; // async reply
  }
});

// --- SSE Connection via fetch + ReadableStream (supports Authorization header) ---
let lastDataAt = 0;           // time of last SSE data received (zombie-connection detection)
const RECONNECT_BASE_MS = 5000;
const ZOMBIE_STALE_MS = 75000; // backend heartbeats every 15s; no data past this → treat as a zombie connection
const TASK_RECONNECT_FAST_INTERVAL_MS = 1000; // 断开后前 30s：每秒重试 1 次
const TASK_RECONNECT_FAST_LIMIT = 30;         // 快速窗口 = 30 次（约 30s）

// --- Shared handler for SSE data lines (user task stream) ---
async function handleSseData(dataLine) {
  // Backend heartbeat (data:ping every 15s) is not JSON: skip silently
  if (!dataLine || !dataLine.startsWith('{')) return;
  try {
    const msg = JSON.parse(dataLine);
    console.log('[AgentSphere] SSE msg:', msg.eventType, msg.action, msg.url?.slice(0,30));
    if (msg.eventType === 'browser_operation') {
      const cmd = msg.command || msg;
      const params = { url: cmd.url, selector: cmd.selector, text: cmd.text, code: cmd.code, mode: cmd.mode, tabId: cmd.tabId, append: cmd.append };
      Object.keys(params).forEach(k => { if (params[k] == null) delete params[k]; });
      console.log('[AgentSphere] Calling executeInPage:', cmd.commandId?.slice(0,8), cmd.action, Object.keys(params));
      await executeInPage(cmd.commandId, cmd.action, params, cmd.sessionId);
    }
  } catch (e) {
    console.warn('[AgentSphere] SSE parse error:', e);
  }
}

// --- User-level task SSE: permanent connection for task-mode browser commands (no session dependency) ---
let taskAbortController = null;
let taskReconnectTimer = null;
let taskConnected = false;
let taskReconnectCount = 0;

async function connectTaskSSE() {
  if (!token || !baseUrl) return;
  if (taskAbortController) { taskAbortController.abort(); taskAbortController = null; }
  if (taskReconnectTimer) { clearTimeout(taskReconnectTimer); taskReconnectTimer = null; }

  const url = `${baseUrl}/api/v1/runtime/user/task/stream`;
  console.log('[AgentSphere] Connecting task SSE:', url);

  taskAbortController = new AbortController();
  try {
    const response = await fetch(url, {
      headers: { 'Authorization': `Bearer ${token}` },
      signal: taskAbortController.signal,
    });
    if (!response.ok) {
      console.warn('[AgentSphere] Task SSE rejected', response.status, '- will retry');
      taskAbortController = null;
      setTaskConnected(false);
      scheduleTaskReconnect();
      return;
    }
    console.log('[AgentSphere] Task SSE connected');
    taskReconnectCount = 0; // 重连成功：回到快速重试窗口
    setTaskConnected(true);

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() || '';
      for (const part of parts) {
        const dataLine = part.startsWith('data:') ? part.slice(5).trim() : '';
        if (!dataLine) continue;
        lastDataAt = Date.now();
        await handleSseData(dataLine);
      }
    }
    console.warn('[AgentSphere] Task SSE stream closed, reconnecting');
    taskAbortController = null;
    setTaskConnected(false);
    scheduleTaskReconnect();
  } catch (e) {
    if (e.name === 'AbortError') return; // aborted by a newer connection; its state will be reported separately
    console.warn('[AgentSphere] Task SSE error:', e.message);
    taskAbortController = null;
    setTaskConnected(false);
    scheduleTaskReconnect();
  }
}

function scheduleTaskReconnect() {
  if (taskReconnectTimer) clearTimeout(taskReconnectTimer);
  // 前 30s 每秒 1 次，之后回落到 5s
  const delay = taskReconnectCount < TASK_RECONNECT_FAST_LIMIT
    ? TASK_RECONNECT_FAST_INTERVAL_MS
    : RECONNECT_BASE_MS;
  taskReconnectCount++;
  taskReconnectTimer = setTimeout(() => { taskReconnectTimer = null; connectTaskSSE(); }, delay);
}

function setTaskConnected(status) {
  taskConnected = status;
  chrome.storage.local.set({ taskConnected: status }).catch(() => {});
}

// (Re)establish the user-level task connection whenever credentials are available
function ensureTaskSSE() {
  if (token && baseUrl) {
    taskReconnectCount = 0; // 新凭证/主动触发：视为全新连接，回到快速重试窗口
    connectTaskSSE();
  }
}

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

// --- Send message to content script with retry ---
async function sendMessageWithRetry(tabId, msg, maxRetries = 5) {
  for (let i = 0; i < maxRetries; i++) {
    try {
      return await chrome.tabs.sendMessage(tabId, msg);
    } catch (e) {
      if (e.message?.includes('Receiving end does not exist') && i < maxRetries - 1) {
        await new Promise(r => setTimeout(r, 500));
        continue;
      }
      throw e;
    }
  }
}

// --- Inject content script into a tab on demand ---
async function injectContentScript(tabId) {
  try {
    await chrome.scripting.executeScript({
      target: { tabId, allFrames: true },
      files: ['content.js'],
    });
    return true;
  } catch (e) {
    // 注入失败：<all_urls> 已授权，剩余场景基本为平台不可注入页面（chrome://、商店页、PDF 等）
    console.warn('[AgentSphere] Content script injection failed for tab', tabId, e?.message);
    return false;
  }
}

// --- When switching to an already-open matching tab: inject content script (ensure bridge present) ---
chrome.tabs.onActivated.addListener((activeInfo) => {
  chrome.tabs.get(activeInfo.tabId, (tab) => {
    if (!tab || tab.id == null || !shouldInject(tab.url || '')) return;
    injectContentScript(tab.id);
  });
});

// --- Execute command in the appropriate tab ---
let controlledTabId = null;
let tabFollowPending = null;
let tabFollowResolve = null;
let attachedTabId = null;

chrome.tabs.onCreated.addListener((tab) => {
  if (tab.openerTabId === controlledTabId) {
    controlledTabId = tab.id;
    injectContentScript(tab.id);
    tabFollowPending = { newTabId: tab.id, url: tab.pendingUrl || tab.url || '', time: Date.now() };
    if (tabFollowResolve) { tabFollowResolve(); tabFollowResolve = null; }
  }
});

chrome.tabs.onRemoved.addListener((tabId) => {
  if (tabId === attachedTabId) {
    attachedTabId = null;
  }
  if (tabId === controlledTabId) {
    console.log('[AgentSphere] Controlled tab closed, resetting');
    controlledTabId = null;
  }
});

async function executeInPage(commandId, action, params, sessionId) {
  console.log('[AgentSphere] executeInPage:', action, params);
  try {
    // Navigate → open new tab and wait for page load
    if (action === 'navigate') {
      // If tabId specified, update that tab instead of creating a new one
      if (params.tabId) {
        try {
          console.log('[AgentSphere] Updating existing tab:', params.tabId);
          const tab = await chrome.tabs.update(params.tabId, { url: params.url });
          await injectContentScript(tab.id);

          await new Promise((resolve) => {
            const listener = (tabId, info) => {
              if (tabId === tab.id && info.status === 'complete') {
                chrome.tabs.onUpdated.removeListener(listener);
                resolve();
              }
            };
            chrome.tabs.onUpdated.addListener(listener);
          });

          await chrome.scripting.executeScript({
            target: { tabId: tab.id },
            func: () => new Promise(r => requestIdleCallback(r, { timeout: 5000 })),
          }).catch(() => {});

          const finalTab = await chrome.tabs.get(tab.id).catch(() => tab);
          const finalUrl = finalTab.url || params.url;

          controlledTabId = tab.id;
          sendCallbackSafe(commandId, {
            success: true,
            data: { tabId: tab.id, url: finalUrl, redirected: finalUrl !== params.url },
            action: 'navigate',
            detail: params.url,
          }, tab.id, sessionId);
          return;
        } catch (e) {
          console.log('[AgentSphere] Failed to update tab, falling back to create:', e.message);
        }
      }

      // Reuse existing tab if same URL is already open
      if (controlledTabId) {
        try {
          const existingTab = await chrome.tabs.get(controlledTabId);
          if (existingTab?.url === params.url || existingTab?.pendingUrl === params.url) {
            console.log('[AgentSphere] Reusing existing tab:', controlledTabId);
            const existingUrl = existingTab.url || params.url;
            sendCallbackSafe(commandId, {
              success: true,
              data: { tabId: controlledTabId, url: existingUrl, redirected: existingUrl !== params.url },
              action: 'navigate',
              detail: params.url,
            }, controlledTabId, sessionId);
            return;
          }
        } catch (e) {
          // Tab no longer exists, create a new one
          console.log('[AgentSphere] Controlled tab gone, creating new one');
        }
      }

      console.log('[AgentSphere] Creating tab with url:', params.url);
      const tab = await chrome.tabs.create({ url: params.url, active: false });
      console.log('[AgentSphere] Tab created:', tab.id);
      await injectContentScript(tab.id);

      // Wait for page to finish loading (HTML + resources)
      await new Promise((resolve) => {
        const listener = (tabId, info) => {
          if (tabId === tab.id && info.status === 'complete') {
            chrome.tabs.onUpdated.removeListener(listener);
            resolve();
          }
        };
        chrome.tabs.onUpdated.addListener(listener);
      });

      // Wait for idle (async rendering / SPA scripts done)
      await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: () => new Promise(r => requestIdleCallback(r, { timeout: 5000 })),
      }).catch(() => {});

      // Get final URL (after potential redirects)
      const finalTab = await chrome.tabs.get(tab.id).catch(() => tab);
      const finalUrl = finalTab.url || params.url;

      controlledTabId = tab.id;
      sendCallbackSafe(commandId, {
        success: true,
        data: { tabId: tab.id, url: finalUrl, redirected: finalUrl !== params.url },
        action: 'navigate',
        detail: params.url,
      }, tab.id, sessionId);
      return;
    }

    // ExecuteJS → use chrome.debugger Runtime.evaluate (persistent attach, no flash)
    if (action === 'executeJS') {
      const targetTabId = params.tabId || controlledTabId || (await getActiveTabId());
      if (!targetTabId) {
        sendCallbackSafe(commandId, { success: false, error: 'No target tab', action }, null, sessionId);
        return;
      }
      try {
        if (attachedTabId !== targetTabId) {
          if (attachedTabId) await chrome.debugger.detach({ tabId: attachedTabId }).catch(() => {});
          await chrome.debugger.attach({ tabId: targetTabId }, "1.3");
          attachedTabId = targetTabId;
        }
        const { result: evalResult } = await chrome.debugger.sendCommand(
          { tabId: targetTabId },
          "Runtime.evaluate",
          { expression: params.code, returnByValue: true }
        );
        const rawValue = evalResult?.value;
        sendCallbackSafe(commandId, {
          success: !evalResult?.exceptionDetails,
          data: rawValue !== undefined ? rawValue : '__NO_RETURN__',
          _resultType: rawValue === undefined ? 'void' : typeof rawValue,
          error: evalResult?.exceptionDetails?.text || null,
          action,
          detail: params.code,
        }, targetTabId, sessionId);
      } catch (e) {
        sendCallbackSafe(commandId, { success: false, error: e.message, action, detail: params.code }, targetTabId, sessionId);
      }
      return;
    }

    // Other actions → send to controlled tab or active tab (or use params.tabId)
    const targetTabId = params.tabId || controlledTabId || (await getActiveTabId());
    console.log('[AgentSphere] Target tab:', targetTabId, 'controlledTabId:', controlledTabId, 'params.tabId:', params.tabId);
    if (!targetTabId) {
      sendCallbackSafe(commandId, { success: false, error: 'No target tab', action }, null, sessionId);
      return;
    }

    const injected = await injectContentScript(targetTabId);
    if (!injected) {
      const tabInfo = await chrome.tabs.get(targetTabId).catch(() => null);
      const url = tabInfo?.url || params.url || '';
      const err = `无法在此标签页执行操作（${url}）：该页面类型不支持注入内容脚本（如 chrome://、扩展商店、PDF 查看器）。`;
      console.warn('[AgentSphere] Inject failed, aborting command:', err);
      sendCallbackSafe(commandId, { success: false, error: err, action, detail: params.selector || params.url || '' }, targetTabId, sessionId);
      return;
    }
    console.log('[AgentSphere] Sending to tab', targetTabId, ':', action, params);

    const result = await sendMessageWithRetry(targetTabId, {
      type: 'browser_operation',
      action,
      params,
    });
    console.log('[AgentSphere] Tab result:', result);

    // Form submit button detected → navigate to extracted URL directly
    if (result?.data?._submitUrl) {
      console.log('[AgentSphere] Form submit detected, navigating to:', result.data._submitUrl);
      executeInPage(commandId, 'navigate', { url: result.data._submitUrl }, sessionId);
      return;
    }

    // New tab auto-follow: click opened a new tab → switch to it
    if (action === 'click' && result?.data?._newTabExpected) {
      await new Promise((resolve) => {
        if (tabFollowPending) return resolve();
        tabFollowResolve = resolve;
        setTimeout(() => { tabFollowResolve = null; resolve(); }, 5000);
      });
      if (tabFollowPending) {
        // Wait for page load complete
        await new Promise((resolve) => {
          const listener = (tabId, info) => {
            if (tabId === tabFollowPending.newTabId && info.status === 'complete') {
              chrome.tabs.onUpdated.removeListener(listener); resolve();
            }
          };
          chrome.tabs.onUpdated.addListener(listener);
          setTimeout(() => { chrome.tabs.onUpdated.removeListener(listener); resolve(); }, 10000);
        });
        // Wait for SPA idle rendering
        await chrome.scripting.executeScript({
          target: { tabId: tabFollowPending.newTabId },
          func: () => new Promise(r => requestIdleCallback(r, { timeout: 3000 })),
        }).catch(() => {});
        // Get final URL (post-redirect)
        const finalTab = await chrome.tabs.get(tabFollowPending.newTabId).catch(() => null);
        if (finalTab) tabFollowPending.url = finalTab.url || tabFollowPending.url;
        result.data._newTabId = tabFollowPending.newTabId;
        result.data._newTabUrl = tabFollowPending.url;
        sendCallbackSafe(commandId, { ...result, action, detail: params.selector || '' }, tabFollowPending.newTabId, sessionId);
        tabFollowPending = null;
        return;
      }
    }

    sendCallbackSafe(commandId, { ...result, action, detail: params.selector || params.url || '' }, targetTabId, sessionId);
  } catch (e) {
    console.error('[AgentSphere] executeInPage error:', e.message);
    sendCallbackSafe(commandId, { success: false, error: e.message, action, detail: e.message }, controlledTabId, sessionId);
  }
}

async function getActiveTabId() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab?.id;
}

// --- Capture screenshot of a tab via chrome.debugger (persistent attach) ---
async function captureScreenshot(tabId) {
  if (!tabId) return null;
  try {
    if (attachedTabId !== tabId) {
      if (attachedTabId) await chrome.debugger.detach({ tabId: attachedTabId }).catch(() => {});
      await chrome.debugger.attach({ tabId }, "1.3");
      attachedTabId = tabId;
    }
    const { data } = await chrome.debugger.sendCommand({ tabId }, "Page.captureScreenshot", {
      format: 'jpeg',
      quality: 60,
    });
    return data;
  } catch (e) {
    return null;
  }
}

// --- Send result back to backend ---
async function sendCallbackSafe(commandId, result, captureTabId, sessionId) {
  try {
    const screenshot = await captureScreenshot(captureTabId);
    console.log('[AgentSphere] captureScreenshot:', screenshot ? `ok ${screenshot.length}chars` : 'null', 'tabId:', captureTabId);
    if (screenshot) {
      sendScreenshotToFrontend(screenshot, result.action, result.detail).catch(() => {});
    }
  } catch (e) {
    console.warn('[AgentSphere] captureScreenshot error:', e.message);
  }
  sendCallback(commandId, result, sessionId).catch(e => {
    console.warn('[AgentSphere] sendCallback rejected:', e.message);
  });
}

// --- Send screenshot directly to the frontend chat tab, bypassing backend ---
async function sendScreenshotToFrontend(screenshot, action, url) {
  const { settings } = await chrome.storage.local.get(['settings']);
  const baseUrl = settings?.mainUrl || settings?.frontendUrl || settings?.frontendUrls?.[0] || 'http://localhost:8000';
  const tabs = await chrome.tabs.query({});
  const chatTab = tabs.find(t => {
    const u = t.url || t.pendingUrl || '';
    return u.startsWith(baseUrl) && u.includes('/chat/');
  });
  console.log('[AgentSphere] sendScreenshot chatTab:', chatTab?.id, chatTab?.url, 'baseUrl:', baseUrl);
  if (!chatTab) return;
  try {
    await chrome.scripting.executeScript({
      target: { tabId: chatTab.id },
      func: (s, u) => {
        window.dispatchEvent(new CustomEvent('page_screenshot', { detail: { screenshot: s, url: u } }));
      },
      args: [screenshot, url || ''],
    });
  } catch (e) {
    console.warn('[AgentSphere] sendScreenshot executeScript failed:', e.message);
  }
}

async function sendCallback(commandId, result, sessionId) {
  try {
    // 回调必须携带指令所属 sessionId（后端必填参数，用于截图事件路由回会话流）
    await fetch(`${baseUrl}/api/v1/chrome/callback?sessionId=${sessionId}`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ commandId, ...result }),
    });

    // Record log
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

/**
 * Service Worker - Maintains SSE connection, routes commands to content script.
 */
console.log('[AgentSphere] Service Worker started', new Date().toISOString());
let token = '';
let sessionId = null;
let baseUrl = '';
let abortController = null;
let reconnectTimer = null;

// --- Keep SW alive: check connection every minute ---
chrome.alarms.create('sse-keepalive', { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'sse-keepalive') {
    if (!abortController || abortController.signal.aborted) {
      console.log('[AgentSphere] Keepalive: reconnecting SSE');
      connectSSE();
    }
  }
});

// --- Start SSE on startup ---
function startSSE() {
  chrome.storage.local.get(['token', 'sessionId', 'baseUrl'], (data) => {
    if (data.token && data.sessionId && data.baseUrl) {
      token = data.token;
      sessionId = data.sessionId;
      baseUrl = data.baseUrl;
      connectSSE();
    }
  });
}

startSSE();

// --- Detect session changes from tab URL (works even without content script) ---
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (!changeInfo.url) return;
  const m = changeInfo.url.match(/\/chat\/(\d+)/);
  if (!m) return;
  const newSessionId = Number(m[1]);
  if (newSessionId === sessionId) return;

  chrome.storage.local.get(['token', 'baseUrl'], (data) => {
    if (data.token && data.baseUrl) {
      token = data.token;
      baseUrl = data.baseUrl;
      sessionId = newSessionId;
      chrome.storage.local.set({ sessionId: newSessionId }).catch(() => {});
      console.log('[AgentSphere] Tab URL changed, switching to session', newSessionId);
      connectSSE();
    }
  });
});

// --- Listen for auth info from content script ---
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === 'auth' && msg.token && msg.sessionId) {
    token = msg.token;
    sessionId = msg.sessionId;
    baseUrl = msg.baseUrl;
    chrome.storage.local.set({
      token, sessionId, displayName: msg.displayName || '', baseUrl: msg.baseUrl,
    }).catch(() => {});
    connectSSE();
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
  // content.js 委托 executeJS → 走 chrome.debugger.Runtime.evaluate（绕过 CSP）
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
    return true; // 异步回复
  }
});

// --- SSE Connection via fetch + ReadableStream (supports Authorization header) ---
async function connectSSE() {
  if (abortController) { abortController.abort(); abortController = null; }
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
  if (!sessionId || !token || !baseUrl) return;

  const url = `${baseUrl}/api/v1/runtime/${sessionId}/stream`;
  console.log('[AgentSphere] Connecting SSE:', url);

  abortController = new AbortController();

  try {
    const response = await fetch(url, {
      headers: { 'Authorization': `Bearer ${token}` },
      signal: abortController.signal,
    });

    if (!response.ok) {
      throw new Error(`SSE connection failed: ${response.status}`);
    }

    console.log('[AgentSphere] SSE connected');
    updatePopupStatus(true);

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
        if (dataLine) {
          try {
            const msg = JSON.parse(dataLine);
            console.log('[AgentSphere] SSE msg:', msg.eventType, msg.action, msg.url?.slice(0,30));
            if (msg.eventType === 'browser_operation') {
              const cmd = msg.command || msg;
              const params = { url: cmd.url, selector: cmd.selector, text: cmd.text, code: cmd.code, mode: cmd.mode, tabId: cmd.tabId, append: cmd.append };
              Object.keys(params).forEach(k => { if (params[k] == null) delete params[k]; });
              console.log('[AgentSphere] Calling executeInPage:', cmd.commandId?.slice(0,8), cmd.action, Object.keys(params));
              await executeInPage(cmd.commandId, cmd.action, params);
            }
          } catch (e) {
            console.error('[AgentSphere] Parse error:', e);
          }
        }
      }
    }
  } catch (e) {
    if (e.name === 'AbortError') return;
    console.warn('[AgentSphere] SSE error, reconnecting in 5s:', e.message);
    updatePopupStatus(false);
    reconnectTimer = setTimeout(connectSSE, 5000);
  }
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
      target: { tabId },
      files: ['content.js'],
    });
  } catch (e) {
    // 已存在或注入失败，静默忽略
  }
}

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

async function executeInPage(commandId, action, params) {
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
          }, tab.id);
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
            }, controlledTabId);
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
      }, tab.id);
      return;
    }

    // ExecuteJS → use chrome.debugger Runtime.evaluate (persistent attach, no flash)
    if (action === 'executeJS') {
      const targetTabId = params.tabId || controlledTabId || (await getActiveTabId());
      if (!targetTabId) {
        sendCallbackSafe(commandId, { success: false, error: 'No target tab', action });
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
        }, targetTabId);
      } catch (e) {
        sendCallbackSafe(commandId, { success: false, error: e.message, action, detail: params.code }, targetTabId);
      }
      return;
    }

    // Other actions → send to controlled tab or active tab (or use params.tabId)
    const targetTabId = params.tabId || controlledTabId || (await getActiveTabId());
    console.log('[AgentSphere] Target tab:', targetTabId, 'controlledTabId:', controlledTabId, 'params.tabId:', params.tabId);
    if (!targetTabId) {
      sendCallbackSafe(commandId, { success: false, error: 'No target tab', action });
      return;
    }

    await injectContentScript(targetTabId);
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
      executeInPage(commandId, 'navigate', { url: result.data._submitUrl });
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
        sendCallbackSafe(commandId, { ...result, action, detail: params.selector || '' }, tabFollowPending.newTabId);
        tabFollowPending = null;
        return;
      }
    }

    sendCallbackSafe(commandId, { ...result, action, detail: params.selector || params.url || '' }, targetTabId);
  } catch (e) {
    console.error('[AgentSphere] executeInPage error:', e.message);
    sendCallbackSafe(commandId, { success: false, error: e.message, action, detail: e.message }, controlledTabId);
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
async function sendCallbackSafe(commandId, result, captureTabId) {
  try {
    const screenshot = await captureScreenshot(captureTabId);
    console.log('[AgentSphere] captureScreenshot:', screenshot ? `ok ${screenshot.length}chars` : 'null', 'tabId:', captureTabId);
    if (screenshot) {
      sendScreenshotToFrontend(screenshot, result.action, result.detail).catch(() => {});
    }
  } catch (e) {
    console.warn('[AgentSphere] captureScreenshot error:', e.message);
  }
  sendCallback(commandId, result).catch(e => {
    console.warn('[AgentSphere] sendCallback rejected:', e.message);
  });
}

// --- Send screenshot directly to the frontend chat tab, bypassing backend ---
async function sendScreenshotToFrontend(screenshot, action, url) {
  const { settings } = await chrome.storage.local.get(['settings']);
  const baseUrl = settings?.frontendUrl || 'http://localhost:8000';
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

async function sendCallback(commandId, result) {
  try {
    const actionResult = result;
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

// --- Notify popup about connection status ---
function updatePopupStatus(connected) {
  chrome.storage.local.set({ connected }).catch(() => {});
}

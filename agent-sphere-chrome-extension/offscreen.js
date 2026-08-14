/**
 * Offscreen document — holds the long-lived user-level task SSE stream and
 * forwards browser_operation commands to the background service worker for
 * execution (offscreen has no tabs API). Immune to MV3 service-worker
 * suspension; the background re-creates this document if the browser closes it.
 *
 * NOTE: offscreen documents support ONLY the chrome.runtime API — no
 * chrome.storage / chrome.tabs. All state (token/baseUrl, taskConnected) is
 * bridged to the background service worker via runtime messages.
 */
let token = '';
let baseUrl = '';
let taskAbortController = null;
let taskReconnectTimer = null;
let lastDataAt = 0;
let taskReconnectCount = 0;
let connected = false;

const RECONNECT_BASE_MS = 5000;
const TASK_RECONNECT_FAST_INTERVAL_MS = 1000; // 断开后前 30s：每秒重试 1 次
const TASK_RECONNECT_FAST_LIMIT = 30;
const ZOMBIE_STALE_MS = 75000; // backend heartbeats every 15s; no data past this → treat as a zombie connection

/** 从 background 拉取凭证（offscreen 无 chrome.storage，必须经 runtime 消息）。 */
async function requestCreds() {
  try {
    const res = await chrome.runtime.sendMessage({
      target: 'background',
      action: 'task:creds',
    });
    if (res && res.token && res.baseUrl) {
      token = res.token;
      baseUrl = res.baseUrl;
      return true;
    }
  } catch (e) {
    // no receiver yet (background suspended/starting) — retry later via keepAlive
  }
  return false;
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg && msg.target === 'offscreen') {
    if (msg.action === 'ping') {
      sendResponse('pong');
      return;
    }
    if (msg.action === 'task:init') {
      requestCreds().then((ok) => {
        if (ok) connectTaskSse();
      });
      sendResponse(true);
      return;
    }
  }
  return false;
});

function setConnected(status) {
  connected = status;
  chrome.runtime.sendMessage({
    target: 'background',
    action: 'task:status',
    connected: status,
  }).catch(() => {});
}

function scheduleTaskReconnect() {
  if (taskReconnectTimer) clearTimeout(taskReconnectTimer);
  const delay = taskReconnectCount < TASK_RECONNECT_FAST_LIMIT
    ? TASK_RECONNECT_FAST_INTERVAL_MS
    : RECONNECT_BASE_MS;
  taskReconnectCount++;
  taskReconnectTimer = setTimeout(() => {
    taskReconnectTimer = null;
    connectTaskSse();
  }, delay);
}

function connectTaskSse() {
  if (!token || !baseUrl) return;
  if (taskAbortController) { taskAbortController.abort(); taskAbortController = null; }
  if (taskReconnectTimer) { clearTimeout(taskReconnectTimer); taskReconnectTimer = null; }

  const url = `${baseUrl}/api/v1/runtime/user/task/stream`;
  taskAbortController = new AbortController();

  (async () => {
    try {
      const response = await fetch(url, {
        headers: { 'Authorization': `Bearer ${token}` },
        signal: taskAbortController.signal,
      });
      if (!response.ok) {
        console.warn('[AgentSphere] Task SSE rejected', response.status, '- will retry');
        taskAbortController = null;
        setConnected(false);
        scheduleTaskReconnect();
        return;
      }
      console.log('[AgentSphere] Task SSE connected');
      taskReconnectCount = 0;
      setConnected(true);

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
          handleSseData(dataLine);
        }
      }
      console.warn('[AgentSphere] Task SSE stream closed, reconnecting');
      taskAbortController = null;
      setConnected(false);
      scheduleTaskReconnect();
    } catch (e) {
      if (e.name === 'AbortError') return; // aborted by a newer connection
      console.warn('[AgentSphere] Task SSE error:', e.message);
      taskAbortController = null;
      setConnected(false);
      scheduleTaskReconnect();
    }
  })();
}

function handleSseData(dataLine) {
  // Backend heartbeat (data:ping every 15s) is not JSON: skip silently
  if (!dataLine || !dataLine.startsWith('{')) return;
  try {
    const msg = JSON.parse(dataLine);
    if (msg.eventType !== 'browser_operation') return;
    const cmd = msg.command || msg;
    const params = {
      url: cmd.url,
      selector: cmd.selector,
      text: cmd.text,
      code: cmd.code,
      mode: cmd.mode,
      tabId: cmd.tabId,
      append: cmd.append,
    };
    Object.keys(params).forEach((k) => { if (params[k] == null) delete params[k]; });
    // Forward to the background service worker; it executes and POSTs the callback.
    chrome.runtime.sendMessage({
      target: 'background',
      action: 'task:command',
      cmd: {
        commandId: cmd.commandId,
        action: cmd.action,
        params,
        sessionId: cmd.sessionId,
      },
    }).catch(() => {});
  } catch (e) {
    console.warn('[AgentSphere] SSE parse error:', e);
  }
}

function keepAlive() {
  if (!token || !baseUrl) {
    // 凭证缺失（offscreen 先于登录启动 / SW 重启后内存态丢失）：重试拉取
    requestCreds().then((ok) => {
      if (ok) connectTaskSse();
    });
    return;
  }
  if (!taskAbortController || taskAbortController.signal.aborted) {
    connectTaskSse();
    return;
  }
  if (lastDataAt && Date.now() - lastDataAt > ZOMBIE_STALE_MS) {
    console.warn('[AgentSphere] Keepalive: no task SSE data for a while, reconnecting');
    taskAbortController.abort();
    taskAbortController = null;
    connectTaskSse();
  }
}

requestCreds().then((ok) => {
  if (ok) connectTaskSse();
  setInterval(keepAlive, 30000);
});

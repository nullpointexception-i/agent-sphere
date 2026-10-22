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
import { loadSessionState, saveSessionState } from './lib/session-state.js';
import { agentCursor } from './lib/agent-cursor.js';
import { browserMetrics } from './lib/browser-metrics.js';

console.log('[AgentSphere] Service Worker started', new Date().toISOString());

let token = '';
let baseUrl = '';
// 最近一次 navigate 的目标 origin：受控 tab 若离开该站点则拒绝继续操作（防跑偏）。
// 持久化到 storage.session —— 只放内存的话 SW 重启后 assertControlledHost 会静默失效。
let targetOrigin = null;
// 命令并发守卫：导航重建 content script 期间悬空的命令不再堆积，避免后端 10s 空等。
// 用时间戳租约：单命令超时（60s）后自动释放，防一条卡死命令锁死整个 session。
// 60s > 后端 executeJS 的 30s 上限：租约若先于命令到期，并发守卫会放行第二条命令，
// 两个命令同时操作同一个 tab。
const COMMAND_LEASE_MS = 60000;
// executeJS 的扩展侧 deadline。刻意比后端 EXECUTEJS_TIMEOUT_SECONDS(30s) 小 3s：
// 两边相等会形成竞态 —— 后端先超时的话会立刻返回并释放 pending future，扩展却还在跑
// 且继续持租约，迟到回调被静默丢弃，下一条命令又撞上并发守卫。
// 27s 让扩展稳定地先返回干净的 errorCategory='timeout' 并正常释放租约。
const EXECJS_TIMEOUT_MS = 27000;
let commandInFlightAt = 0;
// 当前在跑的命令（动作 + 启动时刻），供守卫拒信说明"正在执行什么、还要多久释放"，
// 让后端 LLM 决定等待还是先 getContent 复核，而不是连续空发。
let commandActive = null;

// ---- 风控命中（无人值守：命中即停）----
// 判定：受控 tab 被重定向到安全中心/验证码页。命中后**按 session 锁定**，
// 后续命令一律拒绝 —— 与产品口径「命中就停止，不做等待/重试/绕行」一致。
// 域名特征刻意泛化，不写具体站点品牌：`//safe.` 主机前缀 + captcha/intercept 路径片段
// 已覆盖真实风控跳转。若某站点的风控域不是这个形态，应改为从配置读取而非硬编码品牌。
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

function loadConfiguredUrls(done) {
  chrome.storage.local.get(['settings'], (data) => {
    const s = data.settings || {};
    frontendUrls = Array.isArray(s.frontendUrls)
      ? s.frontendUrls.filter(Boolean)
      : s.frontendUrl
        ? [s.frontendUrl]
        : [];
    mainUrl = s.mainUrl || '';
    // 光标足迹默认开启（显式 false 才关）
    agentCursor.setEnabled(s.agentCursor !== false);
    // 浏览器操作指标默认【关】：数据只留在本机 storage.local，但明细含页面元素文本，
    // 应由用户显式开启。开关关闭时 record* 首行 return，采集零开销。
    browserMetrics.setEnabled(s.browserMetrics === true);
    // UI 存的是 0-100 百分比，模块内部用 0-1 比例
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
  // 风控命中：按 session 锁定，之后一切命令直接拒绝（命中即停，不等待/不重试/不绕行）
  const checkTabId = params.tabId || tabManager.getControlled();
  if (sessionId && riskLockedSessions.has(sessionId)) {
    const res = failResult('风控命中（会话已锁定）：页面曾被重定向到安全中心/验证码。请立即结束任务，不要重试或绕行。', 'risk_challenge');
    sendCallbackSafe(commandId, res, checkTabId, sessionId);
    return;
  }
  const riskHit = await detectRiskChallenge(checkTabId);
  if (riskHit) {
    if (sessionId) riskLockedSessions.add(sessionId);
    const res = failResult(
      `风控命中：受控页已被重定向（url=${riskHit.url}）。请立即结束任务（status=failed），不要重试。`,
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
      `上一命令仍在执行（并发守卫）：${running} 已耗时 ${Math.round(elapsed / 1000)}s，约 ${Math.ceil(retryAfterMs / 1000)}s 后释放；请先 getContent 复核当前状态`,
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

// 坐标点击：基于最后一张 viewport 截图的设备像素 → CSS px → CDP 受信点击；
// 未命中交互元素时裸点并带 warning（真实点击语义，忠实于视觉操作）。
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
        warning = '坐标点未命中可交互元素，已按原生坐标裸点';
      }
    } else if (d && d.ok === false) {
      warning = d.error || '坐标点解析失败，改用裸点';
    }
  } catch (e) {
    warning = 'locatePoint 异常，改用裸点: ' + (e && e.message);
  }
  try {
    // 视觉操作同样画光标：坐标由模型从截图算出，但用户看到的是「鼠标自己动过去点了
    // 那里」，观感与 click/type 一致，也更容易判断模型有没有点偏。
    await agentCursor.show(targetTabId, point);
    await cdpClient.nativeClick(targetTabId, point.x, point.y);
  } catch (e) {
    console.warn('[AgentSphere] clickAt failed:', e?.message);
    sendCallbackSafe(commandId, failResult('clickAt failed: ' + e.message, mapErrorCategory(e)), targetTabId, sessionId);
    return null;
  }
  const data = { action: 'clickAt', x: point.x, y: point.y, _executed: true };
  if (warning) data.warning = warning;
  // post-action 状态：回读 _url/_title/_hints（无 preHash 基线，不虚构 changed）
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
  // content 的结构化失败（断言失败 / 未收敛）此前被整条丢弃：它返回的是
  // {success:false, error, errorCategory}，没有 data，于是 loc.data 为 undefined 直接
  // return null —— 'Element is not enabled' 这类信息永远传不上来，
  // 「目标不可操作」与「目标不存在」在 errorCategory 里被压成同一个 not_found。
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
      // 先把光标移到目标并等它落位（到达屏障），再派发点击
      await agentCursor.show(tabId, loc && loc.point);
      await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y);
      break;
    case 'hover':
      await agentCursor.show(tabId, loc && loc.point);
      await cdpClient.nativeHover(tabId, loc.point.x, loc.point.y);
      break;
    case 'type':
      await agentCursor.show(tabId, loc && loc.point);
      await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y); // 先受信聚焦
      if (params.append !== true) await cdpClient.clearInput(tabId); // insertText 只追加，整字段替换前先清空
      await cdpClient.typeText(tabId, params.text || ''); // 逐字输入（拟人化）
      if (params.submit) await cdpClient.nativeKeyPress(tabId, 'Enter', 'Enter', 13);
      break;
    case 'key': {
      if (loc) {
        // 给了 locator 就会先受信点击聚焦 —— 这一步同样要有光标
        await agentCursor.show(tabId, loc && loc.point);
        await cdpClient.nativeClick(tabId, loc.point.x, loc.point.y);
      }
      const ki = keyInfoOf(params.key);
      await cdpClient.nativeKeyPress(tabId, ki.key, ki.code, ki.keyCode);
      break;
    }
  }
}

// 写动作入口：locate → CDP。
// 点击/悬停会走三档滚动对齐 + 命中校验的降级循环（对应 Codex 的 AZ 三档 +
// DOM.getNodeForLocation 命中比对）—— 元素被 sticky header / 弹层盖住时换一个
// 对齐档位往往就成功了，比直接报错让 LLM 重来快得多。
// 全档失败才报错，且消息里指名拦截者，errorCategory 区分 occluded / ambiguous / not_found。
const ALIGNMENTS = [
  { block: 'center', inline: 'nearest' },
  { block: 'end', inline: 'end' },
  { block: 'start', inline: 'start' },
];
const NEEDS_HIT_TEST = new Set(['click', 'hover']);
// 三档降级的总预算（毫秒）。刻意【不】跟随后端 executeJS 的 30s 超时放大：
// 降级解决的是「点得准不准」，不是「页面慢不慢」。每档的 waitMs 已经给了 2.5s
// 轮询上限；若预算跟随后端等比放大，失败路径会从 ~7s 变成 ~32s，agent 每个
// 空转轮次都烧 3 倍时间。真正慢的页面应该用显式 wait。
const ALIGN_RETRY_BUDGET_MS = 8000;
// 预算之外还要留给：光标到达屏障（≤1.5s，实际 ~250ms）+ 后置校验 0.3s + callback 回传。
const RESERVE_MS = 2500;
// 后置 / 只读调用的固定上界，避免一个卡死的 tab 长期占着命令租约。
const POST_ACTION_TIMEOUT_MS = 4000;

/** 拦截者描述（interceptors 里第一个 <tag#id.class>）。仅用于失败诊断与指标。 */
function firstInterceptor(interceptors) {
  for (const d of interceptors || []) {
    if (typeof d === 'string' && d.startsWith('<')) return d;
  }
  return null;
}

/**
 * 写动作的指标落盘。开关关闭时 browserMetrics.record* 首行即 return，零开销。
 * 不新增任何判定逻辑——这里只搬运降级循环/后置校验已经算出来的结果。
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

/** navigate 的指标落盘（仅耗时 + 是否复用 tab）。开关关闭时零开销。 */
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
  // 同一条命令的三档对齐共享一个 token：content 侧据此复用首档已解析的元素，
  // 后两档不再重走 resolveActionTarget（省时，也避免页面重排后按 ref 重查漂移）。
  const reuseToken = 'r' + commandId + '_' + Date.now();
  let loc = null;
  let done = false;
  // 不可操作类失败：直接终止并报错，不再尝试后续对齐档
  let notInteractable = null;
  // 指标采集：这些只是「把降级循环已经算出来的结果记下来」，不产生任何新判定。
  let alignUsed = null;
  let alignsTried = 0;
  let hitPath = 'none';
  const tStart = Date.now();

  if (!hasLocator(params)) {
    if (action !== 'key') {
      sendCallbackSafe(commandId, failResult(action + ' requires selector/text/ref', ErrorCategory.NOT_FOUND), targetTabId, sessionId);
      return null;
    }
    done = true; // key 无 locator，直接走原有路径
  } else {
    for (let i = 0; i < ALIGNMENTS.length && !done; i++) {
      alignsTried += 1;
      const left = deadline - Date.now();
      if (left <= 500) {
        interceptors.push('timeout');
        break;
      }
      // 按剩余预算摊分给每一档的轮询等待，避免三档叠加撞穿后端超时
      const waitMs = Math.max(0, Math.min(2500, Math.floor(left / (ALIGNMENTS.length - i))));
      loc = await locatePoint(
        targetTabId,
        { ...params, align: ALIGNMENTS[i], waitMs, _reuseToken: reuseToken,
          needHitTest: NEEDS_HIT_TEST.has(action) },
        waitMs + 1500,
      );

      if (!loc || loc.ok === false) {
        // 目标当前不可操作（disabled / 隐藏 / 未收敛）：换对齐档位毫无意义，
        // 再试两档只是白烧预算 —— 直接停，并把具体状态回报给调用方。
        if (loc && loc.errorCategory === ErrorCategory.NOT_INTERACTABLE) {
          notInteractable = loc;
          break;
        }
        if (loc && loc.errorCategory === ErrorCategory.AMBIGUOUS) {
          interceptors.push('ambiguous:' + (loc.matches || 2));
        }
        continue; // not_found / ambiguous 都换下一档
      }
      // 降级成功但仍是多命中：标记出来，调用方据此判断是不是点偏了
      if (loc.ambiguous && !interceptors.some((d) => String(d).startsWith('ambiguous:'))) {
        interceptors.push('ambiguous:' + loc.ambiguous.matches);
      }

      if (NEEDS_HIT_TEST.has(action)) {
        hitPath = loc.hitOk ? 'main' : 'cdp'; // main = content 侧 elementFromPoint 通过；cdp = 走了跨帧 CDP 复核
        let ok = loc.hitOk; // 主帧已由 content 侧 elementFromPoint 自校验
        if (!ok && loc.hitDesc) interceptors.push(loc.hitDesc);
        if (!ok) {
          // 跨源 iframe：elementFromPoint 够不着，用 CDP 在主视口坐标空间复核
          const ht = await cdpClient.hitTest(targetTabId, loc.point.x, loc.point.y);
          if (ht.hit && ht.desc) {
            interceptors.push(ht.desc);
            // 命中元素与目标同 tag → 视为命中（可能是目标的子节点或祖先）
            ok = ht.desc.startsWith('<' + String(loc.tag || '').toLowerCase());
          } else {
            ok = true; // 探测本身失败不阻断，退回原有行为
          }
        }
        if (!ok) continue; // 被挡 → 换下一档
      } else {
        hitPath = 'skipped';
      }
      alignUsed = i;
      done = true;
    }

    if (notInteractable) {
      // 目标存在但此刻不可操作 —— 与「找不到」「被遮挡」是三种不同的处置方式：
      // 这里该等它可用或换目标，原地重试没有意义。
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
    // 回读输入框当前值，供模型核对是否被累加/残留旧词（_echo_ok 表示已覆盖目标文本）。
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

  // 写动作后置校验：CDP 派发后 settle 短暂稳定（flyout/SPA 展开），再读回当前状态。
  // → changed（全页指纹差异）、_selfChanged（目标元素自身状态差异）、_hints、_clickable。
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
      // _selfChanged 回答「我点的那个东西变了吗」。changed 的全页指纹对纯 CSS 展开、
      // 勾选切换、输入值变化一概无感，而这恰恰是最常见的成功反馈 —— 两者互补。
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

    // 非 navigate：先把受控 tab 切到前台，再分发。后台 tab 会被 Chrome 节流
    // （rAF 暂停、setTimeout 钳到 ≥1s，隐藏满 5 分钟到 ~1 次/分钟），而 clickAt /
    // executeJS / screenshot / getContent 及写动作的稳定性等待全都依赖渲染帧 ——
    // 后台下每条动作成倍变慢并撞上后端动作超时。放在所有分支之前才能全覆盖。
    await tabManager.ensureForeground(params.tabId || tabManager.getControlled());

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
      // params.timeout 是协议里已有的字段（ChromeExecuteContext.timeout + offscreen 白名单），
      // 这里只作为「调小」用；上限恒为 EXECJS_TIMEOUT_MS，绝不超过后端的 30s。
      const execTimeout = params.timeout > 0
        ? Math.max(2000, Math.min(EXECJS_TIMEOUT_MS, params.timeout * 1000))
        : EXECJS_TIMEOUT_MS;
      const result = await executeJsOnTab(targetTabId, params.code, execTimeout);
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
        // 角色白名单：浏览器的可访问性角色远多于最初那 15 个，缺失的结构性角色
        // （标题、图片、列表项、对话框等）会让整个页面在 AX 视角下「不可见」。
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
          // 无障碍名（AXName）按 HTML-AAM 计算：纯图标按钮常常是空的，但依然
          // 可交互。原实现直接丢弃它们，恰好丢掉了图标按钮这一整类目标。
          if (!name) nameless++;
          const state = (n.properties || [])
            .filter((p) => ['checked', 'disabled', 'expanded', 'selected', 'focused', 'required'].includes(p.name))
            .map((p) => `${p.name}:${p.value?.value ?? p.value?.type ?? true}`)
            .join(' ');
          // backendDOMNodeId 把 AX 节点接回真实 DOM 节点（原实现丢弃了它，
          // 导致观察到的元素无法回连到 DOM）。本次只输出、不建索引动作路径。
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

    // writeAction 入口：clickAt 走坐标裸点（CDP），与其他写动作并列
    if (action === 'clickAt') {
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
      await clickAtPoint(commandId, params, targetTabId, sessionId);
      return;
    }

    // screenshot：CDP 截图 → 上报后端 → 回调带 fileKey（不发字节过大回调）
    if (action === 'screenshot') {
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

    // 页面级滚动 → CDP mouseWheel（真实滚轮事件 + 分段停顿）；
    // 对元素滚动（给了 selector/text/ref）仍走内容脚本的 scrollIntoView（那是定位动作，非人手势）
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

    // 其余动作 → 内容脚本（只读 / wait / upload / dialog 等）
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
  const navT0 = Date.now();
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
      await saveSessionState({ targetOrigin });
      tabManager.setControlled(tab.id);
      await tabManager.ensureForeground(tab.id);
      tabManager.groupTab(tab.id);
      recordNavigate(commandId, sessionId, navT0, { url: finalUrl, redirected: finalUrl !== params.url, ok: true });
      sendCallbackSafe(commandId, okResult({ tabId: tab.id, url: finalUrl, redirected: finalUrl !== params.url }, 'navigate'), tab.id, sessionId);
      return;
    } catch (e) {
      console.log('[AgentSphere] Failed to update tab, falling back to create:', e.message);
    }
  }

  // Reuse any already-open tab showing this URL, wherever it came from (the
  // controlled tab, a leftover from a previous worker, or one the user opened).
  // Only the controlled tab used to be consulted, and only on an exact string
  // match — so a tab left open across a restart, or differing only by hash/www,
  // always spawned a duplicate.
  const reusable = await tabManager.findReusableTab(params.url);
  if (reusable) {
    try {
      if (await tabManager.ensureContentScript(reusable.id)) {
        const existingUrl = reusable.url || params.url;
        targetOrigin = originOf(existingUrl) || targetOrigin;
        await saveSessionState({ targetOrigin });
        tabManager.setControlled(reusable.id);
        await tabManager.ensureForeground(reusable.id);
        await tabManager.groupTab(reusable.id);
        recordNavigate(commandId, sessionId, navT0, { url: existingUrl, redirected: existingUrl !== params.url, reused: true, ok: true });
        sendCallbackSafe(commandId, okResult({ tabId: reusable.id, url: existingUrl, redirected: existingUrl !== params.url, reused: true }, 'navigate'), reusable.id, sessionId);
        return;
      }
    } catch (e) {
      console.log('[AgentSphere] Reusable tab unusable, creating new one:', e.message);
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
        await saveSessionState({ targetOrigin });
        tabManager.setControlled(existingTab.id);
        await tabManager.ensureForeground(existingTab.id);
        await tabManager.groupTab(existingTab.id);
        recordNavigate(commandId, sessionId, navT0, { url: existingUrl, redirected: existingUrl !== params.url, reused: true, ok: true });
        sendCallbackSafe(commandId, okResult({ tabId: existingTab.id, url: existingUrl, redirected: existingUrl !== params.url, reused: true }, 'navigate'), existingTab.id, sessionId);
        return;
      }
    } catch (e) {
      // Tab no longer exists, create a new one
      console.log('[AgentSphere] Controlled tab gone, creating new one');
    }
  }

  // active:true —— 受控 tab 必须在前台。后台 tab 会被 Chrome 节流（rAF 暂停、
  // 定时器钳到 ≥1s），而插件的稳定性等待全部依赖它们，会导致动作成倍变慢并超时。
  const tab = await chrome.tabs.create({ url: params.url, active: true });
  await tabManager.waitForTabComplete(tab.id, 10000);
  await waitIdle(tab.id);
  if (!await tabManager.ensureContentScript(tab.id)) {
    sendCallbackSafe(commandId, failResult('Content script unavailable after navigation', ErrorCategory.INJECT_FAILED), tab.id, sessionId);
    return;
  }
  const finalTab = await chrome.tabs.get(tab.id).catch(() => tab);
  const finalUrl = finalTab.url || params.url;
  targetOrigin = originOf(finalUrl) || targetOrigin;
  await saveSessionState({ targetOrigin });
  tabManager.setControlled(tab.id);
  await tabManager.ensureForeground(tab.id);
  tabManager.groupTab(tab.id);
  recordNavigate(commandId, sessionId, navT0, { url: finalUrl, redirected: finalUrl !== params.url, ok: true });
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
      // 无返回值：一并回读 document.title，供副作用验证（document.title 标记技巧）
      // 无需再发一轮 getContent —— __NO_RETURN__ 属"执行成功但无返回值"，勿当失败重试。
      if (result === undefined) {
        return { __asVoid: true, __asTitle: typeof document !== 'undefined' ? document.title : '' };
      }
      return result;
    } catch (e) {
      throw new Error('executeJS runtime error: ' + (e && e.message ? e.message : e));
    }
  };
}

/** 给任意 promise 加 deadline。定时器在两条路径都要清理，否则会堆积。 */
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
      'code executed but did not return a value（__NO_RETURN__ 属正常）；副作用已用 document.title 标记的话，读 _documentTitle 复核',
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
    // CDP 层同样要传 deadline：这条路径进的是 cdp-client 的串行队列，
    // 不设界会像早先那样把整个队列毒化（后续 click/insertText 全部排队不执行）。
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

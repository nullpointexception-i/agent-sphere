/**
 * TabManager — per-run tab state, frames, content-script injection and the
 * "AgentSphere" tab group that aggregates every tab the plugin operates on.
 */
import { loadSessionState, saveSessionState, clearSessionState } from './session-state.js';

const CONTENT_FILES = ['content-locator.js', 'content.js'];
// 独立插件窗口的默认尺寸；位置相对用户当前窗口左上角偏移，避免完全盖住用户窗口。
const PLUGIN_WINDOW_SIZE = { width: 1280, height: 860 };
const PLUGIN_WINDOW_OFFSET = 40;
const PLUGIN_WINDOW_LAYOUT = 1;

// content script sendMessage 可能在 tab 导航/重建时永不回包 → 加超时，避免命令悬挂。
function withTimeout(promise, ms, error) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(error), ms);
    promise.then(
      (v) => { clearTimeout(timer); resolve(v); },
      (e) => { clearTimeout(timer); reject(e); },
    );
  });
}

export class TabManager {
  constructor() {
    this.controlledTabId = null;
    this.tabFollowPending = null;
    this.tabFollowResolve = null;
    this.pluginGroupId = null;
    this.pluginWindowId = null;
    // 本 worker 内由 navigate/子 tab 明确收养的 tab；仅内存，用于窗口状态丢失时自愈。
    this.trustedTabId = null;
    // 会话级 tab 归属：插件窗口是全局共享的，上一会话留下的 tab 绝不能被本会话复用/读取。
    // 按 session 分别记账 —— 同一浏览器可能有多个会话并发运行，不能互相清空。
    this.sessionId = null;
    this.sessionTabIds = [];
    this.sessionTabIdsBySession = new Map();
    this.controlledBySession = new Map();
    // 已注入 content scripts 的 tab 缓存（避免每次命令都重注入 + 300ms 停顿）
    this.injectedTabs = new Set();

    // Follow tabs opened by the controlled tab (target=_blank / window.open),
    // but only when the opener is already in the isolated plugin window.
    chrome.tabs.onCreated.addListener((tab) => {
      void (async () => {
        await this.hydrate();
        const wid = await this.getPluginWindowId();
        const opener = tab.openerTabId == null
          ? null
          : await chrome.tabs.get(tab.openerTabId).catch(() => null);
        if (tab.openerTabId !== this.controlledTabId || !opener) return;
        if (wid == null || Number(opener.windowId) !== Number(wid)) {
          // 初次 windows.create 可能继承 extension caller 的 openerTabId。
          // 只有 opener 本身在已验证的插件窗口内，才允许跟随；不能把新窗口移回旧用户窗。
          console.info('[AgentSphere] ignoring tab opened outside the isolated window', {
            tabId: tab.id,
            openerTabId: tab.openerTabId,
            openerWindowId: opener.windowId,
            pluginWindowId: wid,
          });
          return;
        }
        console.log('[AgentSphere] agent-opened tab detected', {
          tabId: tab.id,
          openerTabId: tab.openerTabId,
          fromWindowId: tab.windowId,
          pluginWindowId: wid,
        });
        await this.followOpenedTab(tab);
      })();
    });

    // 页面导航/重新加载后 content scripts 失效 → 失效注入缓存
    chrome.tabs.onUpdated.addListener((tabId, info) => {
      if (info.status === 'loading') {
        this.injectedTabs.delete(tabId);
      }
    });

    chrome.tabs.onRemoved.addListener((tabId) => {
      this.injectedTabs.delete(tabId);
      if (this.isSessionTab(tabId)) {
        this.sessionTabIds = this.sessionTabIds.filter((id) => Number(id) !== Number(tabId));
        void saveSessionState({ tabSessionTabIds: this.sessionTabIds });
      }
      for (const [key, ids] of this.sessionTabIdsBySession) {
        if (ids.some((id) => Number(id) === Number(tabId))) {
          this.sessionTabIdsBySession.set(key, ids.filter((id) => Number(id) !== Number(tabId)));
        }
      }
      for (const [key, id] of this.controlledBySession) {
        if (id != null && Number(id) === Number(tabId)) this.controlledBySession.delete(key);
      }
      if (tabId === this.controlledTabId) {
        this.controlledTabId = null;
        clearSessionState('controlledTabId');
      }
    });

    chrome.tabGroups.onRemoved.addListener((groupId) => {
      if (groupId === this.pluginGroupId) {
        this.pluginGroupId = null;
        clearSessionState('pluginGroupId');
      }
    });

    chrome.windows.onRemoved.addListener((windowId) => {
      if (windowId !== this.pluginWindowId) return;
      void this.clearPluginState();
    });
  }

  /** Service worker 重启后从 storage.session 恢复窗口/分组/受控 tab。 */
  async hydrate() {
    // 迁移只执行一次且必须先完成：并发的 hydrate 不能在迁移中途读到旧状态，
    // 否则会把刚写入的新窗口 id 清掉（表现为 pluginWindowId=null）。
    if (!this.layoutReady) this.layoutReady = this.migrateLayout();
    await this.layoutReady;
    const st = await loadSessionState();
    if (this.pluginWindowId == null && st.pluginWindowId != null) {
      this.pluginWindowId = st.pluginWindowId;
    }
    if (this.pluginGroupId == null && st.pluginGroupId != null) {
      this.pluginGroupId = st.pluginGroupId;
    }
    if (this.controlledTabId == null && st.controlledTabId != null) {
      this.controlledTabId = st.controlledTabId;
    }
    // 只在内存里还没有会话时恢复：beginSession 已经声明了本次会话，不能被旧值覆盖。
    if (this.sessionId == null) {
      if (st.tabSessionId != null) this.sessionId = st.tabSessionId;
      if (Array.isArray(st.tabSessionTabIds)) {
        this.sessionTabIds = st.tabSessionTabIds.filter((id) => id != null);
      }
    }
  }

  /**
   * 声明“当前会话”。插件窗口是全局共享的，上一会话留下的 tab 绝不能被本会话
   * 复用或读取（线上曾读到别的任务残留的站点页面）。
   *
   * <p>切换会话时只换归属登记，**不关闭任何 tab**：同一浏览器里可能还有别的
   * 会话在并发运行，关掉会直接破坏对方。按 session 分别记账，切回来还能续用。
   */
  beginSession(sessionId) {
    if (sessionId == null) return;
    const key = String(sessionId);
    if (this.sessionId != null && String(this.sessionId) === key) return;
    if (this.sessionId != null) {
      const prevKey = String(this.sessionId);
      this.sessionTabIdsBySession.set(prevKey, this.sessionTabIds);
      this.controlledBySession.set(prevKey, this.controlledTabId);
    }
    this.sessionId = sessionId;
    this.sessionTabIds = this.sessionTabIdsBySession.get(key) || [];
    this.controlledTabId = this.controlledBySession.get(key) ?? null;
    this.trustedTabId = this.controlledTabId;
    void saveSessionState({ tabSessionId: sessionId, tabSessionTabIds: this.sessionTabIds });
    if (this.controlledTabId != null) {
      void saveSessionState({ controlledTabId: this.controlledTabId });
    } else {
      void clearSessionState('controlledTabId');
    }
  }

  /** 把 tab 登记为本会话的 tab；只有登记过的 tab 才允许被本会话复用。 */
  registerSessionTab(tabId) {
    if (tabId == null) return;
    if (this.isSessionTab(tabId)) return;
    this.sessionTabIds = [...this.sessionTabIds, tabId];
    if (this.sessionId != null) {
      this.sessionTabIdsBySession.set(String(this.sessionId), this.sessionTabIds);
    }
    void saveSessionState({ tabSessionTabIds: this.sessionTabIds });
  }

  isSessionTab(tabId) {
    if (tabId == null) return false;
    return this.sessionTabIds.some((id) => Number(id) === Number(tabId));
  }

  /**
   * 读取 tab 的真实页面地址（`location.href`）。
   *
   * <p>`chrome.tabs.url` 在重定向/导航竞态下可能是旧值 —— 线上出现过 tabs.url 报
   * 目标页、页面实际停在拦截页的情况。任何“已到达目标页”的判定都必须以页面
   * 自己的 location 为准。读不到（未注入/导航中）时返回 null，由调用方决定是否重新导航。
   */
  async readPageUrl(tabId, timeoutMs = 3000) {
    if (tabId == null) return null;
    try {
      const res = await withTimeout(
        chrome.tabs.sendMessage(tabId, { type: 'agent_sphere_ping' }, { frameId: 0 }),
        timeoutMs,
        new Error('agent_sphere_ping timed out'),
      );
      return res?.url || null;
    } catch (e) {
      return null;
    }
  }

  /**
   * 升级前旧版本把 controlledTabId/pluginGroupId 指向用户窗口里的受控 tab，
   * 这些 legacy 值不能再用来识别插件窗口。只清浏览器操作状态，保留 targetOrigin 等站点安全状态。
   */
  async migrateLayout() {
    const st = await loadSessionState();
    if (st.pluginWindowLayout === PLUGIN_WINDOW_LAYOUT) return;
    await chrome.storage.session.remove([
      'pluginWindowId',
      'pluginGroupId',
      'controlledTabId',
    ]).catch(() => {});
    this.pluginGroupId = null;
    this.controlledTabId = null;
    await saveSessionState({ pluginWindowLayout: PLUGIN_WINDOW_LAYOUT });
  }

  async getPluginWindowId() {
    await this.hydrate();
    return this.pluginWindowId;
  }

  async clearPluginState() {
    this.pluginWindowId = null;
    this.pluginGroupId = null;
    this.controlledTabId = null;
    this.trustedTabId = null;
    // 窗口没了，会话登记的 tab 也都没了；保留内存里的 sessionId（会话可能仍在跑）。
    this.sessionTabIds = [];
    this.sessionTabIdsBySession.clear();
    this.controlledBySession.clear();
    await clearSessionState('pluginWindowId');
    await clearSessionState('pluginGroupId');
    await clearSessionState('controlledTabId');
    await clearSessionState('tabSessionTabIds');
  }

  /**
   * 判断某个 windowId 是否确实是插件窗口：优先看 AgentSphere group，
   * 其次看受控 tab / 本 worker 收养的 tab。防止 window id 被浏览器复用后误认用户窗口。
   */
  async windowLooksLikePlugin(windowId) {
    if (windowId == null) return false;
    // Only accept evidence scoped to the current layout. Legacy controlledTabId
    // used to point at an ordinary user tab and must not identify its window.
    if (this.pluginGroupId != null) {
      const g = await chrome.tabGroups.get(this.pluginGroupId).catch(() => null);
      if (g && Number(g.windowId) === Number(windowId)) return true;
    }
    for (const tabId of [this.trustedTabId]) {
      if (tabId == null) continue;
      const tab = await chrome.tabs.get(tabId).catch(() => null);
      if (tab && Number(tab.windowId) === Number(windowId)) return true;
    }
    return false;
  }

  /**
   * 创建独立窗口并尽量保持用户当前窗口焦点。
   * Chrome 的 focused:false 通常足够；这里再兜底一次，防止某些平台仍把新窗口置前。
   */
  async createUnfocusedWindow(url) {
    const prev = await chrome.windows
      .getLastFocused({ windowTypes: ['normal'] })
      .catch(() => null);
    const bounds = {
      ...PLUGIN_WINDOW_SIZE,
      ...(prev?.left != null && prev?.top != null
        ? { left: prev.left + PLUGIN_WINDOW_OFFSET, top: prev.top + PLUGIN_WINDOW_OFFSET }
        : {}),
    };
    let win = await chrome.windows
      .create({ url, type: 'normal', focused: false, ...bounds })
      .catch((e) => {
        console.warn('[AgentSphere] windows.create(focused:false) failed:', e?.message);
        return null;
      });
    if (!win) {
      // 个别环境/策略下 inactive window 创建失败；退化为普通创建，随后再把焦点还给用户。
      win = await chrome.windows.create({ url, type: 'normal', ...bounds }).catch((e) => {
        console.warn('[AgentSphere] windows.create fallback failed:', e?.message);
        return null;
      });
    }
    if (!win) return win;
    const now = await chrome.windows
      .getLastFocused({ windowTypes: ['normal'] })
      .catch(() => null);
    if (prev && now && now.id === win.id && prev.id !== win.id) {
      await chrome.windows.update(prev.id, { focused: true }).catch(() => {});
    }
    return win;
  }

  /**
   * 在独立插件窗口中打开 URL。窗口已存在时新建 tab；不存在时直接建窗口。
   * 不激活用户窗口，也不复用用户窗口里的 tab。
   */
  async openInPluginWindow(url) {
    const wid = await this.getPluginWindowId();
    if (wid != null) {
      if (await this.windowLooksLikePlugin(wid)) {
        try {
          const tab = await chrome.tabs.create({
            windowId: wid,
            url,
            active: true,
          });
          // 以 tab.windowId 为准（windows.create/get 的 id 在个别环境下可能缺失）。
          this.pluginWindowId = tab.windowId ?? wid;
          await saveSessionState({ pluginWindowId: this.pluginWindowId });
          await this.groupTab(tab.id);
          return tab;
        } catch (e) {
          console.warn('[AgentSphere] plugin window is gone, recreating', wid, e?.message);
        }
      }
      await this.clearPluginState();
    }
    const win = await this.createUnfocusedWindow(url);
    if (!win) {
      console.warn('[AgentSphere] windows.create returned no window');
      return undefined;
    }
    let tab = win.tabs?.[0];
    if (!tab && win.id != null) {
      tab = (await chrome.tabs.query({ windowId: win.id, active: true }))[0];
    }
    if (!tab && win.id != null) {
      // windows.create 偶发返回空窗口：显式补一个 tab，避免 navigate 返回 NO_TAB。
      tab = await chrome.tabs.create({
        windowId: win.id,
        url,
        active: true,
      }).catch(() => null);
    }
    if (!tab?.id) {
      console.warn('[AgentSphere] isolated window created but no tab was returned', {
        windowId: win.id,
      });
      return undefined;
    }
    // Chrome 文档里 Window.id 是 optional；tab.windowId 是更可靠的锚点。
    const windowId = tab.windowId ?? win.id;
    if (windowId == null) {
      console.warn('[AgentSphere] isolated tab has no windowId', {
        windowId: win.id,
        tabId: tab.id,
      });
      return undefined;
    }
    if (win.id != null && Number(tab.windowId) !== Number(win.id)) {
      console.warn('[AgentSphere] new tab landed outside its created window; not moving it', {
        tabId: tab.id,
        tabWindowId: tab.windowId,
        createdWindowId: win.id,
      });
    }
    this.pluginWindowId = tab.windowId ?? windowId;
    this.trustedTabId = tab.id;
    await saveSessionState({ pluginWindowId: this.pluginWindowId });
    await this.groupTab(tab.id);
    console.log('[AgentSphere] isolated window ready', {
      windowId: this.pluginWindowId,
      tabId: tab.id,
      tabWindowId: tab.windowId,
      createdWindowId: win.id,
    });
    return tab;
  }

  async isPluginTab(tabId) {
    if (tabId == null) return false;
    const tab = await chrome.tabs.get(tabId).catch(() => null);
    if (!tab) return false;
    const wid = await this.getPluginWindowId();
    if (wid != null && Number(tab.windowId) === Number(wid)) return true;
    // 本 worker 内 navigate 刚收养过的 tab：只判定，不改写 pluginWindowId，
    // 否则一旦 trustedTabId 指向用户窗口，插件窗口锚点就会被写成用户窗口。
    return this.trustedTabId != null && Number(this.trustedTabId) === Number(tabId);
  }

  async followOpenedTab(tab) {
    try {
      await this.hydrate();
      const wid = await this.getPluginWindowId();
      if (wid == null || !await this.windowLooksLikePlugin(wid)) {
        console.warn('[AgentSphere] refusing to follow tab without a verified plugin window', {
          tabId: tab.id,
          pluginWindowId: wid,
        });
        return;
      }
      const opener = tab.openerTabId == null
        ? null
        : await chrome.tabs.get(tab.openerTabId).catch(() => null);
      if (!opener || Number(opener.windowId) !== Number(wid)) {
        console.warn('[AgentSphere] refusing cross-window tab adoption', {
          tabId: tab.id,
          openerTabId: tab.openerTabId,
          openerWindowId: opener?.windowId,
          pluginWindowId: wid,
        });
        return;
      }
      if (Number(tab.windowId) !== Number(wid)) {
        // Chrome 把子 tab 开到了别的窗口（如 popup）：保持独立，不收养、不移动。
        console.log('[AgentSphere] agent-opened tab stays in its own window', {
          tabId: tab.id,
          tabWindowId: tab.windowId,
          pluginWindowId: wid,
        });
        return;
      }
      this.controlledTabId = tab.id;
      this.trustedTabId = tab.id;
      this.registerSessionTab(tab.id);
      await saveSessionState({ controlledTabId: tab.id });
      await this.injectContentScript(tab.id);
      await this.groupTab(tab.id);
      this.tabFollowPending = {
        newTabId: tab.id,
        url: tab.pendingUrl || tab.url || '',
        time: Date.now(),
      };
      if (this.tabFollowResolve) {
        this.tabFollowResolve();
        this.tabFollowResolve = null;
      }
    } catch (e) {
      console.warn('[AgentSphere] follow opened tab failed:', e?.message);
    }
  }

  setControlled(tabId) {
    this.controlledTabId = tabId;
    this.trustedTabId = tabId;
    if (tabId != null) {
      this.registerSessionTab(tabId);
      if (this.sessionId != null) {
        this.controlledBySession.set(String(this.sessionId), tabId);
      }
    }
    saveSessionState({ controlledTabId: tabId });
  }

  getControlled() {
    return this.controlledTabId;
  }

  /**
   * 把受控 tab 切到前台（其所在窗口内的活动 tab）。
   *
   * <p>后台 tab 会被 Chrome 节流：`requestAnimationFrame` 暂停、`setTimeout` 钳到 ≥1s
   * （隐藏满 5 分钟进一步降到约 1 次/分钟）。而本插件的几个稳定性等待全部依赖 rAF +
   * setTimeout —— 元素几何收敛帧（waitStableRect）、光标到达动画 —— 后台下每条写动作
   * 都要白白多花数秒，叠加后频繁撞上后端的动作超时。
   *
   * <p>只允许激活独立插件窗口内的 tab；用户窗口里的 tab 一律不碰。
   * 刻意只 `tabs.update({active:true})`、不 `windows.update({focused:true})`：
   * 窗口可见但非聚焦时，活动 tab 通常已算 `visible`，既解除节流又不抢用户的 OS 焦点。
   */
  async ensureForeground(tabId) {
    if (!tabId) return;
    try {
      const wid = await this.getPluginWindowId();
      if (wid == null) return;
      const tab = await chrome.tabs.get(tabId);
      if (!tab || Number(tab.windowId) !== Number(wid)) return;
      const win = await chrome.windows.get(wid).catch(() => null);
      if (win?.state === 'minimized') {
        // 恢复成普通窗口但不聚焦：最小化窗口里的 tab 会被 Chrome 当成隐藏页节流。
        await chrome.windows.update(wid, { state: 'normal', focused: false }).catch(() => {});
      }
      if (!tab.active) {
        await chrome.tabs.update(tabId, { active: true });
      }
    } catch (e) {
      // tab 已关闭或不归本窗口管：不阻断调用方，后续动作各自报错即可
    }
  }

  /**
   * Normalise a URL for equality comparison when reusing tabs.
   * The old check was a raw `tab.url === params.url` string compare, which missed
   * every tab differing only by hash / protocol / www / trailing slash.
   */
  normalizeUrl(u) {
    try {
      const url = new URL(u);
      url.hash = '';
      url.protocol = 'https:';
      url.hostname = url.hostname.replace(/^www\./, '');
      if (url.pathname.length > 1) url.pathname = url.pathname.replace(/\/+$/, '');
      return url.toString().toLowerCase();
    } catch (e) {
      return String(u || '').toLowerCase();
    }
  }

  /**
   * Find an already-open tab showing `url` **inside the plugin window only**.
   * Never reuses a user tab: the whole point of the isolated window is that the
   * agent's navigation cannot steal or overwrite the user's current tab.
   *
   * <p>也绝不跨会话复用：插件窗口是全局共享的，只认本会话登记过的 tab。
   * 旧实现会回落到“窗口内任意 tab”，线上因此把上一任务残留的页面当成了本会话的受控页。
   */
  async findReusableTab(url) {
    const want = this.normalizeUrl(url);
    if (!want) return null;
    if (!this.sessionTabIds.length) return null;
    const wid = await this.getPluginWindowId();
    if (wid == null) return null;
    if (!await this.windowLooksLikePlugin(wid)) return null;
    const allowed = new Set(this.sessionTabIds.map((id) => Number(id)));
    const tabs = await chrome.tabs.query({ windowId: wid }).catch(() => []);
    const usable = tabs.filter(
      (t) => t.id != null && allowed.has(Number(t.id)) && /^https?:/.test(t.url || t.pendingUrl || ''),
    );
    const ranked = [
      ...usable.filter((t) => t.id === this.controlledTabId),
      ...usable.filter((t) => t.id !== this.controlledTabId),
    ];
    for (const t of ranked) {
      if (this.normalizeUrl(t.url || t.pendingUrl) === want) return t;
    }
    return null;
  }

  /**
   * Re-aggregate the plugin's tabs after a service-worker restart, which wipes
   * pluginGroupId/pluginWindowId. Only the isolated plugin window is touched;
   * tabs in the user's windows are never moved or grouped.
   */
  async regroupControlledTabs() {
    const wid = await this.getPluginWindowId();
    if (wid == null) return;
    if (!await this.windowLooksLikePlugin(wid)) {
      await this.clearPluginState();
      return;
    }
    const tabs = await chrome.tabs.query({ windowId: wid }).catch(() => []);
    if (!tabs.length) {
      await this.clearPluginState();
      return;
    }
    const st = await loadSessionState();
    this.controlledTabId = st.controlledTabId ?? this.controlledTabId;
    const tid = this.controlledTabId;
    if (tid != null) {
      const tab = await chrome.tabs.get(tid).catch(() => null);
      if (!tab || Number(tab.windowId) !== Number(wid)) {
        this.controlledTabId = null;
        await clearSessionState('controlledTabId');
      }
    }
    for (const t of tabs) {
      if (t.id != null) await this.groupTab(t.id);
    }
  }

  async injectContentScript(tabId) {
    if (this.injectedTabs.has(tabId)) return true;
    try {
      await chrome.scripting.executeScript({
        target: { tabId, allFrames: true },
        files: CONTENT_FILES,
      });
      this.injectedTabs.add(tabId);
      return true;
    } catch (e) {
      // chrome://, extension store, PDF viewer, etc.
      console.warn('[AgentSphere] Content script injection failed for tab', tabId, e?.message);
      return false;
    }
  }

  // Navigation can finish before executeScript is allowed to run. Confirm the
  // top-frame listener is alive instead of reporting a false-ready tab.
  async ensureContentScript(tabId, attempts = 3) {
    for (let i = 0; i < attempts; i++) {
      await this.injectContentScript(tabId);
      try {
        const response = await chrome.tabs.sendMessage(
          tabId,
          { type: 'agent_sphere_ping' },
          { frameId: 0 },
        );
        if (response?.ready) return true;
      } catch (e) {
        this.injectedTabs.delete(tabId);
      }
      if (i < attempts - 1) await new Promise((resolve) => setTimeout(resolve, 300));
    }
    return false;
  }

  // Send a message to a tab's content script, re-injecting when the receiver is missing.
  // frameId 语义：undefined/null=全部框架广播；0=定向顶层 document；>0=定向子框架。
  async askContent(tabId, msg, maxRetries = 3, frameId = 0, timeoutMs = 15000) {
    const sendOpts =
      frameId === undefined || frameId === null ? undefined : { frameId };
    for (let i = 0; i < maxRetries; i++) {
      try {
        return await withTimeout(
          chrome.tabs.sendMessage(tabId, msg, sendOpts),
          timeoutMs,
          new Error('Content script sendMessage timed out'),
        );
      } catch (e) {
        if ((String(e.message).includes('Receiving end does not exist')
            || String(e.message).includes('timed out'))
          && i < maxRetries - 1
          && timeoutMs > 1000) {
          this.injectedTabs.delete(tabId);
          const injected = await this.injectContentScript(tabId);
          if (!injected) break;
          await new Promise((r) => setTimeout(r, 300));
          continue;
        }
        throw e;
      }
    }
    throw new Error('Content script unavailable in tab ' + tabId);
  }

  // Aggregate the tab into the single "AgentSphere" group inside the isolated
  // plugin window (creates it once). The group id is persisted because the
  // service worker restarts and loses it — without that, every restart created
  // a NEW group and the tabs visibly split.
  async groupTab(tabId) {
    try {
      await this.hydrate();
      if (this.pluginWindowId == null) return false;
      const tab = await chrome.tabs.get(tabId).catch(() => null);
      if (!tab) return false;

      // 不在插件窗口里的 tab 只记录、不移动：移动会把用户窗口/新窗口的 tab 互相拽走。
      if (Number(tab.windowId) !== Number(this.pluginWindowId)) {
        console.warn('[AgentSphere] tab is outside the plugin window; not grouping', {
          tabId,
          tabWindowId: tab.windowId,
          pluginWindowId: this.pluginWindowId,
        });
        return false;
      }

      let gid = this.pluginGroupId;
      if (gid != null) {
        // A remembered group id can be stale (user ungrouped/closed it, or it
        // belongs to a previous plugin window) — verify.
        const g = await chrome.tabGroups.get(gid).catch(() => null);
        if (!g || Number(g.windowId) !== Number(this.pluginWindowId)) {
          gid = null;
          this.pluginGroupId = null;
          await clearSessionState('pluginGroupId');
        }
      }
      if (gid != null) {
        await chrome.tabs.group({ tabIds: [tabId], groupId: gid });
        return true;
      }
      // 不指定 windowId 时 Chrome 把新 group 建在“当前窗口”（用户聚焦的窗口），
      // 会把 tab 从插件窗口拽进用户窗口。必须显式指定插件窗口。
      const newId = await chrome.tabs.group({
        tabIds: [tabId],
        createProperties: { windowId: this.pluginWindowId },
      });
      this.pluginGroupId = newId;
      await saveSessionState({ pluginGroupId: newId });
      try {
        await chrome.tabGroups.update(newId, { title: 'AgentSphere', color: 'cyan' });
      } catch (e) {
        /* group styling failure is non-fatal */
      }
      return true;
    } catch (e) {
      // Tabs that cannot be grouped (chrome://, extension pages) land here — as can a
      // genuine failure. Callers may report; swallowing silently hides both.
      console.warn('[AgentSphere] groupTab failed for tab', tabId, e?.message);
      return false;
    }
  }

  waitForTabComplete(tabId, timeoutMs = 20000) {
    return new Promise((resolve) => {
      let settled = false;
      const listener = (id, info) => {
        if (id === tabId && info.status === 'complete') {
          finish();
        }
      };
      const finish = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        chrome.tabs.onUpdated.removeListener(listener);
        resolve();
      };
      const timer = setTimeout(() => {
        finish();
      }, timeoutMs);
      chrome.tabs.onUpdated.addListener(listener);
      // The complete event may have fired before the listener was installed.
      chrome.tabs.get(tabId).then((tab) => {
        if (tab?.status === 'complete') finish();
      }).catch(() => finish());
    });
  }
}

export const tabManager = new TabManager();

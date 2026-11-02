/**
 * TabManager — per-run tab state, frames, content-script injection and the
 * "AgentSphere" tab group that aggregates every tab the plugin operates on.
 */
import { loadSessionState, saveSessionState, clearSessionState } from './session-state.js';

const CONTENT_FILES = ['content-locator.js', 'content.js'];

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
    // 已注入 content scripts 的 tab 缓存（避免每次命令都重注入 + 300ms 停顿）
    this.injectedTabs = new Set();

    // Follow tabs opened by the controlled tab (target=_blank / window.open).
    chrome.tabs.onCreated.addListener((tab) => {
      void (async () => {
        await this.hydrate();
        if (tab.openerTabId === this.controlledTabId) {
          await this.followOpenedTab(tab);
        }
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
    await clearSessionState('pluginWindowId');
    await clearSessionState('pluginGroupId');
    await clearSessionState('controlledTabId');
  }

  /**
   * 判断某个 windowId 是否确实是插件窗口：优先看 AgentSphere group，
   * 其次看受控 tab / 本 worker 收养的 tab。防止 window id 被浏览器复用后误认用户窗口。
   */
  async windowLooksLikePlugin(windowId) {
    if (windowId == null) return false;
    if (this.pluginGroupId != null) {
      const g = await chrome.tabGroups.get(this.pluginGroupId).catch(() => null);
      if (g && Number(g.windowId) === Number(windowId)) return true;
    }
    for (const tabId of [this.controlledTabId, this.trustedTabId]) {
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
    let win = await chrome.windows
      .create({ url, type: 'normal', focused: false })
      .catch((e) => {
        console.warn('[AgentSphere] windows.create(focused:false) failed:', e?.message);
        return null;
      });
    if (!win) {
      // 个别环境/策略下 inactive window 创建失败；退化为普通创建，随后再把焦点还给用户。
      win = await chrome.windows.create({ url, type: 'normal' }).catch((e) => {
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
      tab = await chrome.tabs.move(tab.id, {
        windowId: win.id,
        index: -1,
      }).catch(() => tab);
    }
    this.pluginWindowId = tab.windowId ?? windowId;
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
    // 窗口状态在个别 Chrome 版本上可能短暂丢失；本 worker 内 navigate 刚收养过这个 tab
    // 时，以 tab.windowId 重新锚定，避免 getContent 立刻报 no_tab。
    if (this.trustedTabId != null && Number(this.trustedTabId) === Number(tabId)) {
      this.pluginWindowId = tab.windowId;
      await saveSessionState({ pluginWindowId: tab.windowId });
      return true;
    }
    return false;
  }

  async followOpenedTab(tab) {
    try {
      await this.hydrate();
      this.controlledTabId = tab.id;
      this.trustedTabId = tab.id;
      await saveSessionState({ controlledTabId: tab.id });
      if (this.pluginWindowId != null && Number(tab.windowId) !== Number(this.pluginWindowId)) {
        await chrome.tabs.move(tab.id, {
          windowId: this.pluginWindowId,
          index: -1,
        }).catch(() => {});
      }
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
   */
  async findReusableTab(url) {
    const want = this.normalizeUrl(url);
    if (!want) return null;
    const wid = await this.getPluginWindowId();
    if (wid == null) return null;
    if (!await this.windowLooksLikePlugin(wid)) return null;
    const gid = this.pluginGroupId;
    const tabs = await chrome.tabs.query({ windowId: wid }).catch(() => []);
    const usable = tabs.filter((t) => t.id != null && /^https?:/.test(t.url || t.pendingUrl || ''));
    const ranked = [
      ...usable.filter((t) => t.id === this.controlledTabId),
      ...usable.filter((t) => gid != null && t.groupId === gid),
      ...usable,
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

      // 被子 tab 开到别的窗口时，先移回插件窗口；用户窗口里的 tab 不移动。
      if (Number(tab.windowId) !== Number(this.pluginWindowId)) {
        const moved = await chrome.tabs.move(tabId, {
          windowId: this.pluginWindowId,
          index: -1,
        }).catch(() => null);
        if (!moved) return false;
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
      const newId = await chrome.tabs.group({ tabIds: [tabId] });
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

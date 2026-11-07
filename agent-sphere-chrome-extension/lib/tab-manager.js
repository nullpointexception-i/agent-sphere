/**
 * TabManager — per-run tab state, frames, content-script injection and the
 * "AgentSphere" tab group that aggregates every tab the plugin operates on.
 */
import { loadSessionState, saveSessionState, clearSessionState } from './session-state.js';

const CONTENT_FILES = ['content-locator.js', 'content.js'];
// Default size of the isolated plugin window; its position is offset from the top-left of the user's current window so it does not fully cover it.
const PLUGIN_WINDOW_SIZE = { width: 1280, height: 860 };
const PLUGIN_WINDOW_OFFSET = 40;
const PLUGIN_WINDOW_LAYOUT = 1;

// content script sendMessage may never reply while a tab navigates/rebuilds -> add a timeout to keep commands from hanging.
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
    // Tabs explicitly adopted by navigate/child tabs within this worker; memory only, used to self-heal when window state is lost.
    this.trustedTabId = null;
    // Session-level tab ownership: the plugin window is globally shared, so a tab left by the previous session must never be reused/read by this session.
    // Accounted per session - the same browser may run several sessions concurrently and they must not clear each other.
    this.sessionId = null;
    this.sessionTabIds = [];
    this.sessionTabIdsBySession = new Map();
    this.controlledBySession = new Map();
    // Cache of tabs that already have content scripts injected (avoids re-injecting + a 300ms pause on every command)
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
          // The first windows.create may inherit the extension caller's openerTabId.
          // Follow it only when the opener itself is inside a verified plugin window; the new window must not be moved back into the user's window.
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

    // After page navigation/reload the content scripts are invalid -> invalidate the injection cache
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

  /** Restore windows/groups/controlled tabs from storage.session after a service worker restart. */
  async hydrate() {
    // Migration runs once and must complete first: concurrent hydrate calls must not read stale state mid-migration,
    // otherwise the newly written window id would be cleared (showing up as pluginWindowId=null).
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
    // Only restore when there is no session in memory yet: beginSession has already declared this session, and it must not be overwritten by an old value.
    if (this.sessionId == null) {
      if (st.tabSessionId != null) this.sessionId = st.tabSessionId;
      if (Array.isArray(st.tabSessionTabIds)) {
        this.sessionTabIds = st.tabSessionTabIds.filter((id) => id != null);
      }
    }
  }

  /**
   * Declare the "current session". The plugin window is globally shared, so a tab left by the previous session must never be
   * reused or read by this session (production once read a site page left over from another task).
   *
   * <p>Switching sessions only changes the ownership record, **without closing any tab**: other sessions may still be
   * running concurrently in the same browser, and closing one would break them. Accounting is per session, so switching back can resume.
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

  /** Register a tab as belonging to this session; only registered tabs may be reused by this session. */
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
   * Read a tab's real page address (`location.href`).
   *
   * <p>`chrome.tabs.url` may be stale during a redirect/navigation race - production saw tabs.url report the target page
   * while the page actually stayed on the intercept page. Any "already reached the target page" decision must rely on the page's
   * own location. Returns null when it cannot be read (not injected/navigating), leaving re-navigation to the caller.
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
   * Before the upgrade, older versions pointed controlledTabId/pluginGroupId at a controlled tab inside the user's window;
   * those legacy values can no longer identify the plugin window. Clear only browser operation state and keep site safety state such as targetOrigin.
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
    // The window is gone, so the session's registered tabs are gone too; keep the in-memory sessionId (the session may still be running).
    this.sessionTabIds = [];
    this.sessionTabIdsBySession.clear();
    this.controlledBySession.clear();
    await clearSessionState('pluginWindowId');
    await clearSessionState('pluginGroupId');
    await clearSessionState('controlledTabId');
    await clearSessionState('tabSessionTabIds');
  }

  /**
   * Determine whether a windowId really is a plugin window: check the AgentSphere group first,
   * then controlled tabs / tabs adopted by this worker. Prevents a reused window id from being mistaken for a user window.
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
   * Create an isolated window while keeping the user's current window focused as much as possible.
   * Chrome's focused:false is usually enough; add one more safeguard in case some platforms still bring the new window forward.
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
      // In some environments/policies creating an inactive window fails; degrade to a normal create and then return focus to the user.
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
   * Open a URL in the isolated plugin window. Creates a new tab when the window exists, or creates the window when it does not.
   * Does not activate the user's window, nor reuse tabs from it.
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
          // Trust tab.windowId (windows.create/get ids may be missing in some environments).
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
      // windows.create occasionally returns an empty window: add a tab explicitly so navigate does not return NO_TAB.
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
    // In the Chrome docs Window.id is optional; tab.windowId is the more reliable anchor.
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
    // A tab that navigate just adopted within this worker: only decide, do not rewrite pluginWindowId,
    // otherwise once trustedTabId points at a user window, the plugin window anchor would be written as the user window.
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
        // Chrome opened the child tab in another window (such as a popup): keep it independent, do not adopt or move it.
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
   * Bring the controlled tab to the foreground (the active tab within its window).
   *
   * <p>Background tabs are throttled by Chrome: `requestAnimationFrame` is paused and `setTimeout` is clamped to >=1s
   * (dropping further to about once per minute after 5 minutes hidden). Several of this extension's stability waits depend entirely on rAF +
   * setTimeout - the element geometry settle frame (waitStableRect) and the cursor arrival animation - so in the background every write action
   * wastes several extra seconds and, stacked together, frequently hits the backend action timeout.
   *
   * <p>Only tabs inside the isolated plugin window may be activated; tabs in the user's window are never touched.
   * Deliberately only `tabs.update({active:true})`, never `windows.update({focused:true})`:
   * when the window is visible but unfocused the active tab is usually already counted as `visible`, which lifts throttling without stealing the user's OS focus.
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
        // Restore it to a normal window without focusing: tabs in a minimized window are treated as hidden pages and throttled by Chrome.
        await chrome.windows.update(wid, { state: 'normal', focused: false }).catch(() => {});
      }
      if (!tab.active) {
        await chrome.tabs.update(tabId, { active: true });
      }
    } catch (e) {
      // The tab is closed or not managed by this window: do not block the caller; later actions will each report their own error
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
   * <p>Never reuse across sessions either: the plugin window is globally shared, so only tabs registered to this session count.
   * The old implementation fell back to "any tab in the window", so production treated a page left over from the previous task as this session's controlled page.
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
  // frameId semantics: undefined/null = broadcast to all frames; 0 = target the top-level document; >0 = target a child frame.
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

      // Tabs outside the plugin window are only recorded, never moved: moving would drag tabs between the user's window and the new window.
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
      // Without an explicit windowId, Chrome creates the new group in the "current window" (the user's focused window),
      // which would drag the tab from the plugin window into the user's window. The plugin window must be specified explicitly.
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

/**
 * TabManager — per-run tab state, frames, content-script injection and the
 * "AgentSphere" tab group that aggregates every tab the plugin operates on.
 */
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
    // 已注入 content scripts 的 tab 缓存（避免每次命令都重注入 + 300ms 停顿）
    this.injectedTabs = new Set();

    // Follow tabs opened by the controlled tab (target=_blank / window.open).
    chrome.tabs.onCreated.addListener((tab) => {
      if (tab.openerTabId === this.controlledTabId) {
        this.controlledTabId = tab.id;
        this.injectContentScript(tab.id);
        this.groupTab(tab.id);
        this.tabFollowPending = {
          newTabId: tab.id,
          url: tab.pendingUrl || tab.url || '',
          time: Date.now(),
        };
        if (this.tabFollowResolve) {
          this.tabFollowResolve();
          this.tabFollowResolve = null;
        }
      }
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
      }
    });

    chrome.tabGroups.onRemoved.addListener((groupId) => {
      if (groupId === this.pluginGroupId) this.pluginGroupId = null;
    });
  }

  setControlled(tabId) {
    this.controlledTabId = tabId;
  }

  getControlled() {
    return this.controlledTabId;
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
  async askContent(tabId, msg, maxRetries = 3, frameId = 0) {
    const sendOpts =
      frameId === undefined || frameId === null ? undefined : { frameId };
    for (let i = 0; i < maxRetries; i++) {
      try {
        return await withTimeout(
          chrome.tabs.sendMessage(tabId, msg, sendOpts),
          15000,
          new Error('Content script sendMessage timed out'),
        );
      } catch (e) {
        if ((String(e.message).includes('Receiving end does not exist')
            || String(e.message).includes('timed out'))
          && i < maxRetries - 1) {
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

  // Aggregate the tab into the single "AgentSphere" group (creates it once).
  async groupTab(tabId) {
    try {
      if (this.pluginGroupId != null) {
        await chrome.tabs.group({ tabIds: [tabId], groupId: this.pluginGroupId });
      } else {
        const groupId = await chrome.tabs.group({ tabIds: [tabId] });
        this.pluginGroupId = groupId;
        try {
          await chrome.tabGroups.update(groupId, { title: 'AgentSphere', color: 'cyan' });
        } catch (e) {
          /* group styling failure is non-fatal */
        }
      }
    } catch (e) {
      // Tabs that cannot be grouped (chrome://, extension pages) are silently skipped.
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

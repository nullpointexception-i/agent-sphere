/**
 * TabManager — per-run tab state, frames, content-script injection and the
 * "AgentSphere" tab group that aggregates every tab the plugin operates on.
 */
const CONTENT_FILES = ['content-locator.js', 'content-editors.js', 'content.js'];

export class TabManager {
  constructor() {
    this.controlledTabId = null;
    this.tabFollowPending = null;
    this.tabFollowResolve = null;
    this.pluginGroupId = null;

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

    chrome.tabs.onRemoved.addListener((tabId) => {
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
    try {
      await chrome.scripting.executeScript({
        target: { tabId, allFrames: true },
        files: CONTENT_FILES,
      });
      return true;
    } catch (e) {
      // chrome://, extension store, PDF viewer, etc.
      console.warn('[AgentSphere] Content script injection failed for tab', tabId, e?.message);
      return false;
    }
  }

  // Send a message to a tab's content script, re-injecting when the receiver is missing.
  async askContent(tabId, msg, maxRetries = 3) {
    for (let i = 0; i < maxRetries; i++) {
      try {
        return await chrome.tabs.sendMessage(tabId, msg);
      } catch (e) {
        if (String(e.message).includes('Receiving end does not exist') && i < maxRetries - 1) {
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
      const listener = (id, info) => {
        if (id === tabId && info.status === 'complete') {
          clearTimeout(timer);
          chrome.tabs.onUpdated.removeListener(listener);
          resolve();
        }
      };
      const timer = setTimeout(() => {
        chrome.tabs.onUpdated.removeListener(listener);
        resolve();
      }, timeoutMs);
      chrome.tabs.onUpdated.addListener(listener);
    });
  }
}

export const tabManager = new TabManager();

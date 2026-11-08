/**
 * Session state — survives MV3 service-worker suspension.
 *
 * The background service worker is killed after ~30s idle and every module-scope
 * or `this` field resets to its initial value. Three pieces of navigation state
 * used to live only in memory and silently vanished on every restart:
 *
 *   pluginGroupId    → the AgentSphere group id   → a new group on every restart (tabs split)
 *   pluginWindowId   → the isolated plugin window → tabs fall back into the user's window
 *   targetOrigin     → the controlled-site guard  → assertControlledHost() goes inert
 *   controlledTabId  → the controlled tab         → navigate() stops reusing tabs
 *
 * chrome.storage.session is the right store: readable after a worker restart,
 * cleared when the browser exits, and invisible to other extensions.
 *
 * NEVER call this from offscreen.js — offscreen documents have no chrome.storage.
 */
const KEYS = [
  'pluginGroupId',
  'pluginWindowId',
  'pluginWindowLayout',
  'targetOrigin',
  'controlledTabId',
  'tabSessionId',
  'tabSessionTabIds',
  'focusOwned',
  'focusRestoreWindowId',
];

export async function loadSessionState() {
  try {
    return await chrome.storage.session.get(KEYS);
  } catch (e) {
    // storage.session unavailable (very old Chrome / restricted profile) → degrade to memory only
    return {};
  }
}

export async function saveSessionState(patch) {
  try {
    await chrome.storage.session.set(patch);
  } catch (e) {
    /* degrade to in-memory state; navigation still works, just loses SW-restart durability */
  }
}

export async function clearSessionState(key) {
  try {
    await chrome.storage.session.remove(key);
  } catch (e) {
    /* ignore */
  }
}

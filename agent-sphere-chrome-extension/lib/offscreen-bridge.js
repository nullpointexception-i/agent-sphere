/**
 * Offscreen bridge (background side) — (re)creates and pings the offscreen
 * document that holds the long-lived task SSE stream and command queue.
 */
const OFFSCREEN_URL = 'offscreen.html';

export async function queryOffscreenExists() {
  try {
    if (typeof chrome.runtime.getContexts === 'function') {
      const contexts = await chrome.runtime.getContexts({
        contextTypes: ['OFFSCREEN_DOCUMENT'],
      });
      return contexts.some((c) => c.url === chrome.runtime.getURL(OFFSCREEN_URL));
    }
  } catch (e) {
    /* fall through to ping probe */
  }
  try {
    const res = await chrome.runtime.sendMessage({ target: 'offscreen', action: 'ping' });
    return res === 'pong';
  } catch (e) {
    return false;
  }
}

export async function ensureOffscreenDocument() {
  if (await queryOffscreenExists()) return true;
  try {
    await chrome.offscreen.createDocument({
      url: OFFSCREEN_URL,
      reasons: ['IFRAME_SCRIPTING'],
      justification: 'Keep the task SSE stream and command queue alive.',
    });
    return true;
  } catch (e) {
    return false;
  }
}

export async function askOffscreen(message) {
  try {
    return await chrome.runtime.sendMessage({ target: 'offscreen', ...message });
  } catch (e) {
    return null;
  }
}

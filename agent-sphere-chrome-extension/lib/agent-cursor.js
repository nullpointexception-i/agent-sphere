/**
 * AgentCursor — visible agent pointer for write actions.
 *
 * CDP trusted input dispatches at a coordinate with no on-screen hint, so from
 * the user's side the page just reacts by itself. Codex renders a custom cursor
 * and makes the caller WAIT for the animation to land before dispatching —
 * otherwise the pointer visibly "teleports" between targets.
 *
 * Protocol (mirrors Codex's AGENT_CURSOR_STATE):
 *   background → content : {type:'AGENT_CURSOR_STATE', x, y, animateMovement, moveSequence}
 *   content   → background : {type:'notifyCursorArrived', moveSequence}
 *
 * The rendering itself lives in content.js, which mutates the page DOM directly
 * from the ISOLATED world — the same mechanism highlightElement() already uses.
 * No MAIN-world injection, no postMessage bridge: content scripts share the DOM,
 * so an extra world hop only added ways for the cursor to silently not appear.
 *
 * The arrival barrier is the point of this module, not the pixel. Without it the
 * cursor would still be mid-flight when the next click lands.
 */
const ARRIVAL_TIMEOUT_MS = 1500;

export class AgentCursor {
  constructor() {
    this.sessionId = null;
    this.turnId = null;
    this.moveSeq = 0;
    this.enabled = true;
  }

  setContext(sessionId, turnId) {
    this.sessionId = sessionId;
    this.turnId = turnId;
  }

  setEnabled(v) {
    this.enabled = !!v;
  }

  async show(tabId, point, opts) {
    if (!this.enabled || !point) return;
    const seq = ++this.moveSeq;
    let ack = null;
    try {
      ack = await chrome.tabs.sendMessage(tabId, {
        type: 'AGENT_CURSOR_STATE',
        visible: true,
        x: point.x,
        y: point.y,
        // Without a renderer (hidden/restricted) teleport instead of running the animation for nothing
        animateMovement: !(opts && opts.instant),
        moveSequence: seq,
        sessionId: this.sessionId,
        turnId: this.turnId,
      });
    } catch (e) {
      // No content script / restricted page — the click still proceeds, just without a visual.
      return;
    }
    // If the content script never took over, drop the barrier immediately; do not make every action wait 1.5s for nothing
    // (matching Codex: cancel as soon as publish fails instead of waiting for the Vf timeout)
    if (!ack || ack.ok !== true || ack.rendered === false) return;
    await this.waitArrival(seq);
  }

  /** Resolve once the content script reports the animation finished, or on timeout. */
  waitArrival(seq, timeoutMs = ARRIVAL_TIMEOUT_MS) {
    return new Promise((resolve) => {
      const finish = () => {
        clearTimeout(timer);
        chrome.runtime.onMessage.removeListener(onMsg);
        resolve();
      };
      const timer = setTimeout(finish, timeoutMs);
      const onMsg = (msg) => {
        if (msg && msg.type === 'notifyCursorArrived' && msg.moveSequence === seq) finish();
      };
      chrome.runtime.onMessage.addListener(onMsg);
    });
  }
}

export const agentCursor = new AgentCursor();
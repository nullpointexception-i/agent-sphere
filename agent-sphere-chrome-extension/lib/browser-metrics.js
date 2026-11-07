/**
 * BrowserMetrics — local-only accuracy / success-rate analytics for write actions.
 *
 * Everything lives in chrome.storage.local under two keys and never leaves the
 * browser: no backend call, no new callback field, no wire-protocol change.
 *
 *   browserMetrics         counters + latency samples (lightweight, always read)
 *   browserMetricsRecent   bounded ring buffer of per-action detail (read only
 *                          when the Metrics tab is open — it is ~55KB and would
 *                          otherwise re-render the whole popup on every action)
 *
 * Split by weight on purpose: popup.js listens to storage.onChanged for ANY key,
 * so keeping the detail buffer in its own key means automation writes don't
 * thrash the popup while you are reading a different tab.
 *
 * Shape rationale:
 *   - counters are MONOTONIC (never trimmed) so rates stay valid over time;
 *     the 300-record buffer is only for "what just happened" + manual audit.
 *   - `selfChanged === undefined` means the target fingerprint was unavailable
 *     (no stable ref). Those samples are counted in `selfChangedUnknown` and are
 *     excluded from BOTH numerator and denominator of the mis-click rate —
 *     folding them in would silently make the rate look better than it is.
 */

const STATE_KEY = 'browserMetrics';
const RECENT_KEY = 'browserMetricsRecent';
const SCHEMA_VERSION = 1;

const RECENT_CAP = 300;
const LATENCY_CAP = 200;
// Ring buffer for per-session rollups. Sessions are transient; keeping every
// session id forever would grow unbounded without adding analytical value.
const SESSION_CAP = 100;

const DEFAULT_SAMPLE_RATE = 0.05;

// Auditable verdicts. Null means "not judged yet".
export const VERDICT_CORRECT = 'correct';
export const VERDICT_WRONG = 'wrong_element';
export const VERDICT_NO_EFFECT = 'no_effect';

function emptyState() {
  return {
    v: SCHEMA_VERSION,
    startedAt: Date.now(),
    updatedAt: Date.now(),
    lastRecordAt: 0,
    counters: {
      ops: 0, ok: 0, fail: 0,
      changed: 0, selfChanged: 0, selfChangedUnknown: 0,
      effectCounted: 0,          // actions that succeeded and (changed || selfChanged) = numerator of the effectiveness rate
      echoOk: 0, echoFail: 0,
      misclickSuspect: 0,
      degraded: 0, retargeted: 0, settledFail: 0,
    },
    byCategory: {},
    byAction: {},
    byAlign: { 0: 0, 1: 0, 2: 0 },
    byHitPath: { main: 0, cdp: 0, skipped: 0, none: 0 },
    sessions: {},
    latencies: [],
  };
}

function emptyRecent() {
  return [];
}

class BrowserMetrics {
  constructor() {
    this.enabled = false;
    this.sampleRate = DEFAULT_SAMPLE_RATE;
    // Serialises read-modify-write cycles. Without it two concurrent records can
    // read the same base state and the second write silently drops the first.
    this._chain = Promise.resolve();
  }

  // --- configuration -------------------------------------------------------

  setEnabled(v) {
    this.enabled = !!v;
  }

  isEnabled() {
    return this.enabled;
  }

  setSampleRate(rate) {
    const n = Number(rate);
    this.sampleRate = Number.isFinite(n) && n > 0 && n <= 1 ? n : DEFAULT_SAMPLE_RATE;
  }

  getSampleRate() {
    return this.sampleRate;
  }

  // --- write path ----------------------------------------------------------

  _mutate(fn) {
    this._chain = this._chain
      .then(async () => {
        const got = await chrome.storage.local.get([STATE_KEY, RECENT_KEY]);
        const state = got[STATE_KEY] || emptyState();
        const recent = got[RECENT_KEY] || emptyRecent();
        const out = fn(state, recent) || {};
        await chrome.storage.local.set({
          [STATE_KEY]: out.state || state,
          [RECENT_KEY]: out.recent || recent,
        });
      })
      .catch((e) => {
        // Analytics must never break automation.
        console.warn('[AgentSphere] metrics write failed:', e?.message);
      });
    return this._chain;
  }

  /**
   * A write action (click/type/hover/key/select/upload/clickAt).
   * `diag` carries values writeAction already computed; no new decision logic
   * lives here — this module only persists and aggregates.
   */
  recordWriteAction(op) {
    if (!this.enabled) return Promise.resolve();
    const now = Date.now();
    const selfChanged = op.selfChanged === undefined ? undefined : !!op.selfChanged;
    const record = {
      t: now,
      commandId: op.commandId || '',
      sessionId: op.sessionId || null,
      action: op.action || '',
      ok: !!op.ok,
      cat: op.cat || null,
      ms: Number(op.ms) || null,
      alignUsed: op.alignUsed == null ? null : op.alignUsed,
      alignsTried: op.alignsTried == null ? null : op.alignsTried,
      hitPath: op.hitPath || 'none',
      degraded: !!op.degraded,
      matches: op.matches == null ? null : op.matches,
      retargeted: !!op.retargeted,
      settled: op.settled === false ? false : null,
      assertState: op.assertState || null,
      interceptor: op.interceptor || null,
      tag: op.tag || '',
      text: op.text || '',
      changed: op.changed === undefined ? undefined : !!op.changed,
      selfChanged,
      echoOk: op.echoOk == null ? null : !!op.echoOk,
      needsAudit: Math.random() < this.sampleRate,
      verdict: null,
    };

    return this._mutate((state, recent) => {
      applyCommon(state, record, now);
      const c = state.counters;
      c.changed += record.changed === true ? 1 : 0;
      // Counts the "decidable sample count" (both true and false), because it is the denominator of the misclick rate.
      // Accumulating only true would shrink the denominator and inflate the misclick rate.
      if (selfChanged === undefined) c.selfChangedUnknown += 1;
      else c.selfChanged += 1;
      // Effectiveness numerator: succeeded, and either the page or the target element changed
      if (record.ok && (record.changed === true || selfChanged === true)) c.effectCounted += 1;
      // Suspicious misclick: the page changed but "the element I clicked" did not -> strongly suspect the wrong target.
      // This is the strongest automatic proxy metric for precision (changed is the whole-page fingerprint; _selfChanged looks only at the target itself).
      if (record.ok && record.changed === true && selfChanged === false) {
        c.misclickSuspect += 1;
      }
      if (record.echoOk === true) c.echoOk += 1;
      if (record.echoOk === false) c.echoFail += 1;
      if (record.degraded) c.degraded += 1;
      if (record.retargeted) c.retargeted += 1;
      if (record.settled === false) c.settledFail += 1;

      if (record.ok && record.alignUsed != null) {
        const k = String(record.alignUsed);
        state.byAlign[k] = (state.byAlign[k] || 0) + 1;
      }
      const hp = record.hitPath;
      if (hp) state.byHitPath[hp] = (state.byHitPath[hp] || 0) + 1;

      return { state, recent: pushRecent(recent, record) };
    });
  }

  /** navigate — lighter: timing + whether we reused an existing tab. */
  recordNavigate(op) {
    if (!this.enabled) return Promise.resolve();
    const now = Date.now();
    const record = {
      t: now,
      commandId: op.commandId || '',
      sessionId: op.sessionId || null,
      action: 'navigate',
      ok: !!op.ok,
      cat: op.cat || null,
      ms: Number(op.ms) || null,
      alignUsed: null, alignsTried: null, hitPath: 'skipped',
      degraded: false, matches: null, retargeted: false, settled: null,
      assertState: null, interceptor: null,
      tag: '', text: op.reused ? '[reused tab]' : '',
      changed: undefined, selfChanged: undefined, echoOk: null,
      needsAudit: false,
      verdict: null,
    };
    return this._mutate((state, recent) => {
      applyCommon(state, record, now);
      return { state, recent: pushRecent(recent, record) };
    });
  }

  /** getContent / screenshot / executeJS — timing + outcome only. */
  recordSimple(action, ok, cat, ms, sessionId, commandId) {
    if (!this.enabled) return Promise.resolve();
    const now = Date.now();
    const record = {
      t: now,
      commandId: commandId || '',
      sessionId: sessionId || null,
      action: action || '',
      ok: !!ok,
      cat: cat || null,
      ms: Number(ms) || null,
      alignUsed: null, alignsTried: null, hitPath: 'none',
      degraded: false, matches: null, retargeted: false, settled: null,
      assertState: null, interceptor: null,
      tag: '', text: '',
      changed: undefined, selfChanged: undefined, echoOk: null,
      needsAudit: false,
      verdict: null,
    };
    return this._mutate((state, recent) => {
      applyCommon(state, record, now);
      // Don't buffer every read op — the detail view is about writes.
      return { state, recent };
    });
  }

  // --- read path -----------------------------------------------------------

  /** Aggregated view; every ratio is computed here so callers can't disagree. */
  snapshot() {
    return chrome.storage.local.get(STATE_KEY).then((got) => {
      const s = got[STATE_KEY];
      if (!s || !s.counters || s.counters.ops === 0) return emptySnapshot();
      return buildSnapshot(s);
    });
  }

  getRecent(limit = 20, filter = {}) {
    return chrome.storage.local.get(RECENT_KEY).then((got) => {
      let rows = got[RECENT_KEY] || [];
      if (filter.onlySuspect) rows = rows.filter((r) => r.changed === true && r.selfChanged === false);
      if (filter.onlyFailed) rows = rows.filter((r) => !r.ok);
      if (filter.onlyUnaudited) rows = rows.filter((r) => r.needsAudit && !r.verdict);
      if (filter.cat) rows = rows.filter((r) => r.cat === filter.cat);
      rows = sortRecent(rows);
      return rows.slice(0, limit);
    });
  }

  setVerdict(t, commandId, verdict) {
    if (!commandId) return Promise.resolve();
    return this._mutate((state, recent) => {
      const hit = recent.find((r) => r.t === t && r.commandId === commandId);
      if (hit) hit.verdict = verdict;
      return { state, recent };
    });
  }

  reset() {
    return chrome.storage.local.set({ [STATE_KEY]: emptyState(), [RECENT_KEY]: emptyRecent() });
  }

  exportJson() {
    return chrome.storage.local.get([STATE_KEY, RECENT_KEY]).then((got) => JSON.stringify({
      exportedAt: new Date().toISOString(),
      state: got[STATE_KEY] || emptyState(),
      recent: got[RECENT_KEY] || [],
    }, null, 2));
  }
}

function applyCommon(state, record, now) {
  const c = state.counters;
  c.ops += 1;
  if (record.ok) c.ok += 1; else c.fail += 1;
  if (!record.ok && record.cat) {
    state.byCategory[record.cat] = (state.byCategory[record.cat] || 0) + 1;
  }
  const a = state.byAction[record.action] || (state.byAction[record.action] = { ops: 0, ok: 0 });
  a.ops += 1;
  if (record.ok) a.ok += 1;

  if (Number.isFinite(record.ms)) {
    state.latencies.push(Math.round(record.ms));
    if (state.latencies.length > LATENCY_CAP) {
      state.latencies.splice(0, state.latencies.length - LATENCY_CAP);
    }
  }

  if (record.sessionId != null) {
    const s = state.sessions[record.sessionId] || (state.sessions[record.sessionId] = { ops: 0, ok: 0, firstOk: null });
    s.ops += 1;
    if (record.ok) s.ok += 1;
    if (s.firstOk === null) s.firstOk = record.ok;
    const ids = Object.keys(state.sessions);
    if (ids.length > SESSION_CAP) {
      // Drop oldest-inserted (object key order is insertion order for string keys).
      for (let i = 0; i < ids.length - SESSION_CAP; i++) delete state.sessions[ids[i]];
    }
  }

  state.updatedAt = now;
  state.lastRecordAt = now;
}

function pushRecent(recent, record) {
  recent.push(record);
  if (recent.length > RECENT_CAP) recent.splice(0, recent.length - RECENT_CAP);
  return recent;
}

/** Suspicious misclicks first, then sampled pending review, finally newest first - sampling only affects the flag, not sort visibility. */
function sortRecent(rows) {
  return rows.slice().sort((a, b) => {
    const sa = a.changed === true && a.selfChanged === false ? 1 : 0;
    const sb = b.changed === true && b.selfChanged === false ? 1 : 0;
    if (sa !== sb) return sb - sa;
    const ua = a.needsAudit && !a.verdict ? 1 : 0;
    const ub = b.needsAudit && !b.verdict ? 1 : 0;
    if (ua !== ub) return ub - ua;
    return b.t - a.t;
  });
}

function pct(num, den) {
  if (!den) return null;
  return (num / den) * 100;
}

function percentile(sorted, p) {
  if (!sorted.length) return null;
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

function emptySnapshot() {
  return {
    hasData: false,
    startedAt: null, updatedAt: null, lastRecordAt: null,
    ops: 0, ok: 0, fail: 0,
    execRate: null, effectRate: null, typeRate: null,
    decidableRate: null, misclickRate: null, echoFailRate: null,
    degradedRate: null, retargetRate: null, unsettledRate: null,
    crossFrameRate: null,
    alignRescued: null,
    byAlign: { 0: 0, 1: 0, 2: 0 },
    byCategory: {}, byAction: {}, byHitPath: {},
    lat: { p50: null, p95: null, max: null },
    sessions: { count: 0, firstTryRate: null, avgOps: null },
  };
}

function buildSnapshot(s) {
  const c = s.counters;
  const typeAction = s.byAction.type || { ops: 0, ok: 0 };
  const decidable = c.selfChanged + c.selfChangedUnknown;
  const sorted = s.latencies.slice().sort((a, b) => a - b);

  let sessionCount = 0;
  let firstTryOk = 0;
  let totalOps = 0;
  for (const k of Object.keys(s.sessions)) {
    const v = s.sessions[k];
    sessionCount += 1;
    totalOps += v.ops;
    if (v.firstOk) firstTryOk += 1;
  }

  const alignRescuedTotal = (s.byAlign['1'] || 0) + (s.byAlign['2'] || 0);
  const alignOkTotal = (s.byAlign['0'] || 0) + alignRescuedTotal;

  return {
    hasData: true,
    startedAt: s.startedAt, updatedAt: s.updatedAt, lastRecordAt: s.lastRecordAt,
    ops: c.ops, ok: c.ok, fail: c.fail,
    // L1 execution rate: the command did not error
    execRate: pct(c.ok, c.ops),
    // L2 effectiveness rate: how many successful actions produced a page reaction (denominator is ok, not ops -
    //    failures are already reflected in the execution rate; each metric says one thing, avoiding double penalties)
    effectRate: pct(c.effectCounted, c.ok),
    // L3 type achievement rate: the input value exactly equals the target text (hard metric)
    typeRate: pct(c.echoOk, typeAction.ok),
    decidableRate: pct(c.selfChanged, decidable),
    misclickRate: pct(c.misclickSuspect, c.selfChanged),
    echoFailRate: pct(c.echoFail, typeAction.ok),
    degradedRate: pct(c.degraded, c.ops),
    retargetRate: pct(c.retargeted, c.ops),
    unsettledRate: pct(c.settledFail, c.ops),
    crossFrameRate: pct(s.byHitPath.cdp, c.ops),
    alignRescued: pct(alignRescuedTotal, alignOkTotal),
    byAlign: { 0: s.byAlign['0'] || 0, 1: s.byAlign['1'] || 0, 2: s.byAlign['2'] || 0 },
    byCategory: s.byCategory,
    byAction: s.byAction,
    byHitPath: s.byHitPath,
    lat: {
      p50: percentile(sorted, 50),
      p95: percentile(sorted, 95),
      max: sorted.length ? sorted[sorted.length - 1] : null,
    },
    sessions: {
      count: sessionCount,
      firstTryRate: pct(firstTryOk, sessionCount),
      avgOps: sessionCount ? totalOps / sessionCount : null,
    },
  };
}

export const browserMetrics = new BrowserMetrics();

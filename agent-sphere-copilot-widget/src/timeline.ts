import type { TimelineQuery } from './api';
import type { SessionTimelinePageVO, TimelineRow } from './types';

/** Cursor: oldestSeq = the earliest loaded row, newestSeq = the latest loaded row. */
export interface TimelineCursors {
  oldestSeq: number | null;
  newestSeq: number | null;
}

export const EMPTY_CURSORS: TimelineCursors = { oldestSeq: null, newestSeq: null };

/**
 * Local optimistic-state guard key for clarifications. It must include clarificationId: the backend's resumeFromClarification reuses
 * the same runId to continue while the run is AWAITING_USER, and in one session the agent may be asked several rounds of questions,
 * each with a different clarificationId but a shared runId. Using runId alone would make the "second-round clarification" look already answered,
 * so its options and input box would not render (recovering only after a refresh rebuilds the Ref). When the id is missing it returns null -> skip the guard and trust the server.
 */
export function clarificationKey(runId: unknown, clarificationId: unknown): string | null {
  if (runId == null || clarificationId == null || clarificationId === '') return null;
  return `${runId}:${clarificationId}`;
}

/**
 * Merge a timeline page by seq (a later row overwrites an older row with the same seq) and drop placeholder subagent rows replaced by authoritative ones
 * (seq<0 real-time placeholders; once the authoritative sub-agent row arrives, the placeholder with the same refSubAgentRunId is removed). Returns rows in ascending seq order.
 */
export function mergeTimeline(
  prev: TimelineRow[],
  rows: TimelineRow[],
  answeredKeys?: Set<string>,
): TimelineRow[] {
  if (rows.length === 0) {
    return prev;
  }
  const map = new Map<number, TimelineRow>();
  for (const r of prev) {
    map.set(r.seq, r);
  }
  for (const r of rows) {
    if (
      r.kind === 'subagent' &&
      r.seq >= 0 &&
      r.refSubAgentRunId != null &&
      Number(r.refSubAgentRunId) > 0
    ) {
      // Authoritative subagent row arrived -> remove placeholders with the same refSubAgentRunId
      for (const [seq, existing] of [...map.entries()]) {
        if (
          seq < 0 &&
          existing.kind === 'subagent' &&
          existing.refSubAgentRunId != null &&
          Number(existing.refSubAgentRunId) === Number(r.refSubAgentRunId)
        ) {
          map.delete(seq);
        }
      }
    }
    let incoming = r;
    // Clarification already answered locally: a stale PENDING from the server must not roll back the optimistic state. Matched by clarificationId;
    // when the id is missing nothing is blocked (see the clarificationKey comment).
    if (
      answeredKeys &&
      r.kind === 'clarification' &&
      r.state === 'PENDING'
    ) {
      const key = clarificationKey(r.runId, r.content?.clarificationId);
      if (key && answeredKeys.has(key)) {
        incoming = { ...r, state: 'ANSWERED' };
      }
    }
    // When an assistant row is refreshed in place, the authoritative row may have no body yet (streaming tokens exist only locally).
    // A running empty row must not overwrite body/reasoning already streamed out, otherwise the reply the user is reading suddenly disappears.
    const existing = map.get(incoming.seq);
    if (
      existing &&
      incoming.kind === 'assistant' &&
      (!incoming.state || incoming.state === 'RUNNING') &&
      !(incoming.content?.text ?? '').trim() &&
      !(incoming.content?.thinking ?? '').trim() &&
      ((existing.content?.text ?? '').trim() ||
        (existing.content?.thinking ?? '').trim())
    ) {
      incoming = {
        ...incoming,
        content: {
          ...incoming.content,
          text: incoming.content?.text || existing.content?.text,
          thinking: incoming.content?.thinking || existing.content?.thinking,
        },
      };
    }
    map.set(incoming.seq, incoming);
  }
  return [...map.values()].sort((a, b) => a.seq - b.seq);
}

/** Merge the cursor using the latest page. */
export function updateCursors(
  cursors: TimelineCursors,
  page: SessionTimelinePageVO,
): void {
  const seqs = page.rows.map((r) => r.seq);
  if (seqs.length === 0) {
    return;
  }
  const min = Math.min(...seqs);
  const max = Math.max(...seqs);
  if (cursors.oldestSeq == null || min < cursors.oldestSeq) {
    cursors.oldestSeq = min;
  }
  if (cursors.newestSeq == null || max > cursors.newestSeq) {
    cursors.newestSeq = max;
  }
}

/** Initial load: the latest page at the tail. */
export async function fetchInitialTimeline(
  page: (q: TimelineQuery) => Promise<SessionTimelinePageVO>,
  limit = 10,
): Promise<{ rows: TimelineRow[]; hasMore: boolean; cursors: TimelineCursors }> {
  const res = await page({ limit });
  const cursors: TimelineCursors = { ...EMPTY_CURSORS };
  updateCursors(cursors, res);
  return { rows: res.rows, hasMore: res.hasMore, cursors };
}

/** Page backwards (older). */
export async function fetchOlderTimeline(
  page: (q: TimelineQuery) => Promise<SessionTimelinePageVO>,
  cursors: TimelineCursors,
  limit = 8,
): Promise<{ rows: TimelineRow[]; hasMore: boolean }> {
  if (cursors.oldestSeq == null) {
    return { rows: [], hasMore: false };
  }
  const res = await page({ beforeSeq: cursors.oldestSeq, limit });
  updateCursors(cursors, res);
  return { rows: res.rows, hasMore: res.hasMore };
}

/** Catch up to the latest after a disconnect/new event (afterSeq; with an empty cursor, load the tail page). */
export async function fetchLatestTimeline(
  page: (q: TimelineQuery) => Promise<SessionTimelinePageVO>,
  cursors: TimelineCursors,
  limit = 10,
): Promise<{ rows: TimelineRow[]; hasMore: boolean }> {
  const res =
    cursors.newestSeq == null
      ? await page({ limit })
      : await page({ afterSeq: cursors.newestSeq, limit });
  updateCursors(cursors, res);
  return { rows: res.rows, hasMore: res.hasMore };
}

/**
 * Full-page overwrite refresh after tool events: fetch the tail window (no afterSeq). An afterSeq delta skips existing rows with seq <= newest
 * (tool rows), so content that lands late (such as browser screenshot images) is invisible during the live phase;
 * the tail window overwrite corrects rows with the same seq.
 */
export async function fetchTailTimeline(
  page: (q: TimelineQuery) => Promise<SessionTimelinePageVO>,
  limit = 30,
): Promise<{ rows: TimelineRow[]; hasMore: boolean }> {
  const res = await page({ limit });
  return { rows: res.rows, hasMore: res.hasMore };
}
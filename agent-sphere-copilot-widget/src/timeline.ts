import type { TimelineQuery } from './api';
import type { SessionTimelinePageVO, TimelineRow } from './types';

/** 游标：oldestSeq = 已载入的最早行，newestSeq = 已载入的最新行。 */
export interface TimelineCursors {
  oldestSeq: number | null;
  newestSeq: number | null;
}

export const EMPTY_CURSORS: TimelineCursors = { oldestSeq: null, newestSeq: null };

/**
 * 按 seq 合并 timeline 页（后到行覆盖同 seq 旧行），并剔除被权威行替换掉的占位 subagent 行
 * （seq<0 实时占位；权威子 Agent 行到达后，同 refSubAgentRunId 的占位行移除）。返回按 seq 升序。
 */
export function mergeTimeline(
  prev: TimelineRow[],
  rows: TimelineRow[],
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
      // 权威 subagent 行到达 → 移除同 refSubAgentRunId 的占位行
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
    map.set(r.seq, r);
  }
  return [...map.values()].sort((a, b) => a.seq - b.seq);
}

/** 用最新一页合并游标。 */
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

/** 初始加载：尾部最新一页。 */
export async function fetchInitialTimeline(
  page: (q: TimelineQuery) => Promise<SessionTimelinePageVO>,
  limit = 10,
): Promise<{ rows: TimelineRow[]; hasMore: boolean; cursors: TimelineCursors }> {
  const res = await page({ limit });
  const cursors: TimelineCursors = { ...EMPTY_CURSORS };
  updateCursors(cursors, res);
  return { rows: res.rows, hasMore: res.hasMore, cursors };
}

/** 向上翻页（更早）。 */
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

/** 断线/新事件后补最新（afterSeq；空游标则拉尾部页）。 */
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
 * 工具事件后的整页覆盖刷新：拉尾窗（无 afterSeq）。afterSeq 增量会跳过 seq ≤ newest 的
 * 既有行（工具行），导致 live 阶段看不到迟到落库的 content（如浏览器截图 images）；
 * 以尾窗覆盖修正同 seq 行。
 */
export async function fetchTailTimeline(
  page: (q: TimelineQuery) => Promise<SessionTimelinePageVO>,
  limit = 30,
): Promise<{ rows: TimelineRow[]; hasMore: boolean }> {
  const res = await page({ limit });
  return { rows: res.rows, hasMore: res.hasMore };
}
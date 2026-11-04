import type { TimelineQuery } from './api';
import type { SessionTimelinePageVO, TimelineRow } from './types';

/** 游标：oldestSeq = 已载入的最早行，newestSeq = 已载入的最新行。 */
export interface TimelineCursors {
  oldestSeq: number | null;
  newestSeq: number | null;
}

export const EMPTY_CURSORS: TimelineCursors = { oldestSeq: null, newestSeq: null };

/**
 * 澄清乐观态的本地保护键。必须包含 clarificationId：后端 resumeFromClarification 在 run
 * 处于 AWAITING_USER 时会复用同一个 runId 续跑，一次会话里 agent 可能被追问多轮，
 * 各轮 clarificationId 不同却共用 runId。只用 runId 会让「第二轮澄清」被误判成已应答，
 * 待选项和输入框不渲染（刷新后 Ref 重建才恢复）。缺 id 时返回 null → 放弃保护、采信服务端。
 */
export function clarificationKey(runId: unknown, clarificationId: unknown): string | null {
  if (runId == null || clarificationId == null || clarificationId === '') return null;
  return `${runId}:${clarificationId}`;
}

/**
 * 按 seq 合并 timeline 页（后到行覆盖同 seq 旧行），并剔除被权威行替换掉的占位 subagent 行
 * （seq<0 实时占位；权威子 Agent 行到达后，同 refSubAgentRunId 的占位行移除）。返回按 seq 升序。
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
    let incoming = r;
    // 已本地应答的澄清：服务端旧 PENDING 不得回退乐观态。按 clarificationId 匹配，
    // 缺 id 时不拦截（见 clarificationKey 注释）。
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
    // assistant 行原地刷新时，权威行可能还没有正文（流式 token 只存在于本地）。
    // 运行中的空行不得覆盖已经流出的正文/推理，否则用户看到的回复会突然消失。
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
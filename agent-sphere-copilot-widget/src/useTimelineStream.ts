import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiClient, TimelineQuery } from './api';
import { connectSse } from './sse';
import { stripSubAgentSentinel } from './subAgentMarker';
import {
  fetchInitialTimeline,
  fetchLatestTimeline,
  fetchOlderTimeline,
  fetchTailTimeline,
  mergeTimeline,
  clarificationKey,
  updateCursors,
  type TimelineCursors,
} from './timeline';
import type {
  ClarificationBatch,
  SubAgentTimelineItemVO,
  TimelineRow,
  ToolApprovalBatch,
} from './types';

/** 子 Agent 实时步骤（纯 SSE 聚合，终态由一次性历史拉取校正）。 */
export type SubAgentLiveStep =
  | {
      type: 'llm';
      reasoning: string;
      reply: string;
      running: boolean;
    }
  | {
      type: 'tool_call';
      publishId: string;
      toolName?: string;
      displayNameCn?: string;
      displayNameEn?: string;
      status: 'pending' | 'in_progress' | 'succeeded' | 'failed';
      argumentsJson?: string;
      artifact?: string;
      /** 工具结果中的浏览器截图引用（[{fileKey, contentType}]），live 步骤回显用。 */
      images?: { fileKey: string; contentType?: string }[];
    };

export type SubAgentLiveMap = Record<number, SubAgentLiveStep[]>;

const PULL_TRIGGER_SUB_TYPES = [
  'run_running',
  'run_completed',
  'run_failed',
  'run_cancelled',
  'run_awaiting_user',
  // wait 挂起：到点会自行恢复同一个 run，需要拉一次让前端显示「等待中」。
  // 故意不在 RUN_TERMINAL_SUB_TYPES 里 —— 它不是终态，加进去会让等待期间
  // 就解锁输入框，等同于把「等时间」误报成「任务结束」。
  'run_waiting',
  'tool_call_succeeded',
  'tool_call_failed',
  // 压缩终态：拉一次权威行（正文来自 summary_after）。running 不拉 —— 浅合并会覆盖已流出的正文。
  'compaction_completed',
  'compaction_failed',
];

/** 从工具结果 artifact JSON 解析浏览器截图引用（data.screenshot.fileKey/contentType）。 */
function parseScreenshotRef(artifact?: string | null): { fileKey: string; contentType?: string } | null {
  if (!artifact) return null;
  try {
    const root = JSON.parse(artifact);
    const shot = root?.data?.screenshot;
    if (!shot?.fileKey) return null;
    return { fileKey: String(shot.fileKey), contentType: shot.contentType || 'image/jpeg' };
  } catch {
    return null;
  }
}

/** run 终态子类型：到达即代表当前 run 结束（解锁输入/恢复发送按钮）。 */
const RUN_TERMINAL_SUB_TYPES = [
  'run_completed',
  'run_failed',
  'run_cancelled',
  'run_awaiting_user',
] as const;

interface TimelineStream {
  rows: TimelineRow[];
  hasMore: boolean;
  loadingOlder: boolean;
  subAgentLiveMap: SubAgentLiveMap;
  /** 乐观插入、尚未被后端权威行替换的用户消息（seq<0，渲染在 timeline 末尾）。 */
  pendingUserRows: TimelineRow[];
  /** 事件驱动的“当前会话是否有 run 在跑”（run_running/打字机=true；终态=false）。 */
  runActive: boolean;
  loadInitial: (sessionId: number) => Promise<void>;
  loadOlder: (sessionId: number) => Promise<void>;
  refreshLatest: (sessionId: number) => Promise<void>;
  /** 工具事件后整页覆盖刷新（补迟到落库的截图 images 等）。 */
  refreshTail: (sessionId: number) => Promise<void>;
  /** 传播中的用户消息：不入库，先本地上墙，等权威行到达后移除；同时置 runActive=true（点击即锁输入）。 */
  addUserMessage: (text: string, images?: { fileKey: string; contentType?: string }[]) => void;
  removeUserMessage: (text: string) => void;
  /** 发送失败等场景手动复位 runActive。 */
  markRunInactive: () => void;
  /** 整包 SSE 事件（RuntimeEventVO {eventType, data}）→ timeline 打字机/子 Agent live/拉新。 */
  handleSseEvent: (parsed: Record<string, unknown>) => void;
  /** 子 Agent 权威时间线拉取（终态校正）。 */
  loadSubAgentSteps: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  /** 建立会话 SSE 连接（会话切换时调用）；需传 apiBase 以构造正确的流地址（同源相对或跨域绝对）。 */
  connect: (sessionId: number, token: string, apiBase: string) => void;
  /** 乐观提交澄清后登记已应答（防 refreshLatest 回退乐观态）。 */
  markClarificationAnswered: (runId: number, clarificationId?: string | null) => void;
  /** L2 tool approval batch shown in the bottom drawer. */
  toolApproval: ToolApprovalBatch | null;
  resolveToolApproval: (decisions: Array<{ callId: string; decision: string }>) => Promise<void>;
  clarification: ClarificationBatch | null;
  resolveClarifications: (
    answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
  ) => Promise<void>;
}

/** 子 Agent LIVE 占位行 seq：负且按 subAgentRunId 唯一（避免同 seq 冲突）。 */
function placeholderSeq(subAgentRunId: number): number {
  return -(Math.abs(subAgentRunId) + 1);
}

function parseApproval(payload: any): ToolApprovalBatch | null {
  if (!payload?.approvalId || !payload?.toolCallsJson) return null;
  try {
    const calls = JSON.parse(payload.toolCallsJson);
    const pending = Array.isArray(calls)
      ? calls.filter((c: any) => c?.pending !== false)
      : [];
    if (pending.length === 0) return null;
    return {
      approvalId: String(payload.approvalId),
      runId: Number(payload.runId),
      calls: pending,
    };
  } catch {
    return null;
  }
}

export function useTimelineStream(api: ApiClient): TimelineStream {
  const [rows, setRows] = useState<TimelineRow[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [subAgentLiveMap, setSubAgentLiveMap] = useState<SubAgentLiveMap>({});
  const [pendingUserRows, setPendingUserRows] = useState<TimelineRow[]>([]);
  const [runActive, setRunActiveState] = useState(false);
  const [toolApproval, setToolApproval] = useState<ToolApprovalBatch | null>(null);
  const [clarification, setClarification] = useState<ClarificationBatch | null>(null);
  const pendingSeqRef = useRef(0);
  const cursorsRef = useRef<TimelineCursors>({ oldestSeq: null, newestSeq: null });
  const sessionIdRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  /** 已本地应答的澄清 runId：防止 refreshLatest 用服务端旧 PENDING 回退乐观态。 */
  const answeredClarificationKeysRef = useRef<Set<string>>(new Set());
  const reconnectAttemptRef = useRef(0);
  const reconnectTimerRef = useRef<number | null>(null);

  const getPage = useCallback(
    (sessionId: number) => (q: TimelineQuery) =>
      api.getTimeline(sessionId, q),
    [api],
  );

  /** 权威行到达后，清掉文本匹配的乐观用户行（避免重复气泡）。 */
  const reconcilePendingUserRows = useCallback((freshRows: TimelineRow[]) => {
    const authoritativeTexts = new Set<string>();
    for (const r of freshRows) {
      if (r.kind === 'user' && r.seq >= 0 && r.content?.text) {
        authoritativeTexts.add(r.content.text.trim());
      }
    }
    if (authoritativeTexts.size === 0) return;
    setPendingUserRows((prev) => {
      if (prev.length === 0) return prev;
      const next = prev.filter(
        (p) => !authoritativeTexts.has((p.content?.text ?? '').trim()),
      );
      return next.length === prev.length ? prev : next;
    });
  }, []);

  const addUserMessage = useCallback(
    (text: string, images?: { fileKey: string; contentType?: string }[]) => {
      pendingSeqRef.current -= 1;
      const seq = pendingSeqRef.current;
      // 纯图片发送时给非空占位文本，兼容 reconcile 按 text 去重的既有逻辑（权威行到达后替换）
      const displayText = text.trim() ? text : '[图片]';
      setPendingUserRows((prev) => [
        ...prev,
        {
          seq,
          kind: 'user',
          state: 'COMPLETED',
          content: { text: displayText, ...(images && images.length ? { images } : {}) },
        },
      ]);
      // 发送即锁定输入（无需等 run_running）——事件驱动锁定的“即时”来源
      setRunActiveState(true);
    },
    [],
  );

  const removeUserMessage = useCallback((text: string) => {
    const wanted = String(text).trim();
    setPendingUserRows((prev) =>
      prev.filter((p) => (p.content?.text ?? '').trim() !== wanted),
    );
  }, []);

  const markRunInactive = useCallback(() => {
    setRunActiveState(false);
  }, []);

  const loadInitial = useCallback(
    async (sessionId: number) => {
      try {
        const page = getPage(sessionId);
        const { rows: pageRows, hasMore: m, cursors } =
          await fetchInitialTimeline(page, 10);
        cursorsRef.current = cursors;
        setRows(pageRows);
        setHasMore(m);
        reconcilePendingUserRows(pageRows);
        // 兜底：刷新会话时若“最新”一条 run_status 仍是 RUNNING → 视为有 run 在跑（事件会随后校正）
        for (let i = pageRows.length - 1; i >= 0; i--) {
          const r = pageRows[i];
          if (r.kind === 'run_status') {
            if (r.state === 'RUNNING') {
              setRunActiveState(true);
            }
            break;
          }
        }
      } catch {
        // 加载失败保持空态，SSE 终态事件仍会触发 refreshLatest 尝试补
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [getPage],
  );

  const loadOlder = useCallback(
    async (sessionId: number) => {
      if (loadingOlder) return;
      if (cursorsRef.current.oldestSeq == null) return;
      setLoadingOlder(true);
      try {
        const { rows: older, hasMore: m } = await fetchOlderTimeline(
          getPage(sessionId),
          cursorsRef.current,
          8,
        );
        setRows((prev) => mergeTimeline(prev, older, answeredClarificationKeysRef.current));
        setHasMore(m);
      } catch {
        // 翻页失败保持现状
      } finally {
        setLoadingOlder(false);
      }
    },
    [getPage, loadingOlder],
  );

  const refreshLatest = useCallback(
    async (sessionId: number) => {
      try {
        const { rows: fresh } = await fetchLatestTimeline(
          getPage(sessionId),
          cursorsRef.current,
          10,
        );
        setRows((prev) => mergeTimeline(prev, fresh, answeredClarificationKeysRef.current));
        reconcilePendingUserRows(fresh);
      } catch {
        // 补拉失败忽略（SSE 打字机仍会就地更新）
      }
    },
    [getPage, reconcilePendingUserRows],
  );

  const refreshTail = useCallback(
    async (sessionId: number) => {
      try {
        const { rows: fresh } = await fetchTailTimeline(getPage(sessionId), 30);
        if (fresh.length) {
          setRows((prev) => mergeTimeline(prev, fresh, answeredClarificationKeysRef.current));
          // 更新游标：尾窗视作权威最新，推动 afterSeq
          updateCursors(cursorsRef.current, {
            rows: fresh,
            hasMore: false,
            oldestSeq: null,
            newestSeq: null,
          });
        }
        reconcilePendingUserRows(fresh);
      } catch {
        // 补拉失败忽略
      }
    },
    [getPage, reconcilePendingUserRows],
  );

  // ---- 子 Agent 实时步骤聚合（主站 handleSubAgentLiveEvent 移植） ----
  const handleSubAgentLiveEvent = useCallback((evtType: string, d: any) => {
    const subId = Number(d.subAgentRunId);
    if (!Number.isFinite(subId)) return;
    const delta = String(d?.response ?? '');

    if (evtType === 'content_token') {
      setSubAgentLiveMap((prev) => {
        const arr: SubAgentLiveStep[] = prev[subId] || [];
        if (!arr.length) {
          return { ...prev, [subId]: [{ type: 'llm', reasoning: '', reply: delta, running: true }] };
        }
        const last = arr[arr.length - 1];
        if (last.type !== 'llm' || !last.running) {
          return {
            ...prev,
            [subId]: [...arr, { type: 'llm', reasoning: '', reply: delta, running: true }],
          };
        }
        const idx = arr.length - 1;
        const lastLlm = last as SubAgentLiveStep & { type: 'llm' };
        return {
          ...prev,
          [subId]: arr.map((it, i) =>
            i === idx ? { ...it, reply: lastLlm.reply + delta } : it,
          ),
        };
      });
      return;
    }

    if (evtType === 'reasoning_token') {
      const subType = String(d?.reasoningSubType || '');
      if (
        subType === 'tool_call_started' ||
        subType === 'tool_call_in_progress' ||
        subType === 'tool_call_succeeded' ||
        subType === 'tool_call_failed'
      ) {
        const status: 'pending' | 'in_progress' | 'succeeded' | 'failed' =
          subType === 'tool_call_started'
            ? 'pending'
            : subType === 'tool_call_in_progress'
              ? 'in_progress'
              : subType === 'tool_call_succeeded'
                ? 'succeeded'
                : 'failed';
        const update: any = {
          type: 'tool_call',
          publishId: String(d?.publishId || ''),
          displayNameCn: d?.displayNameCn || d?.displayName,
          displayNameEn: d?.displayNameEn,
          toolName: d?.toolName,
        };
        if (d?.argumentsJson) update.argumentsJson = d.argumentsJson;
        if (d?.artifact) update.artifact = d.artifact;
        // 工具结果含浏览器截图 → live 步骤直接带 images（刷新后走后端 timeline 一致）
        const shot = parseScreenshotRef(update.artifact);
        if (shot) update.images = [shot];
        setSubAgentLiveMap((prev) => {
          const arr: SubAgentLiveStep[] = prev[subId] || [];
          const idx = arr.findIndex(
            (it) => it.type === 'tool_call' && it.publishId === update.publishId,
          );
          if (idx >= 0) {
            return {
              ...prev,
              [subId]: arr.map((it, i) =>
                i === idx ? { ...it, ...update, status } : it,
              ),
            };
          }
          return { ...prev, [subId]: [...arr, { ...update, status }] };
        });
        return;
      }

      if (subType === 'model_reason') {
        if (d?.firstFrame) {
          // 新 LLM 轮：剥离首帧哨兵行（"<marker>id: name\n"），旧轮标记结束
          // 新旧前缀均接受（`▶ Agent ` / `▶ Skill `），避免历史会话断裂
          const body = stripSubAgentSentinel(delta);
          setSubAgentLiveMap((prev) => {
            const arr: SubAgentLiveStep[] = prev[subId] || [];
            const closed = arr.map((it) =>
              it.type === 'llm' && it.running ? { ...it, running: false } : it,
            );
            return {
              ...prev,
              [subId]: [...closed, { type: 'llm', reasoning: body, reply: '', running: true }],
            };
          });
        } else {
          setSubAgentLiveMap((prev) => {
            const arr: SubAgentLiveStep[] = prev[subId] || [];
            if (!arr.length) {
              return { ...prev, [subId]: [{ type: 'llm', reasoning: delta, reply: '', running: true }] };
            }
            const last = arr[arr.length - 1];
            if (last.type !== 'llm' || !last.running) {
              return {
                ...prev,
                [subId]: [...arr, { type: 'llm', reasoning: delta, reply: '', running: true }],
              };
            }
            const idx = arr.length - 1;
            const lastLlmReason = last as SubAgentLiveStep & { type: 'llm' };
            return {
              ...prev,
              [subId]: arr.map((it, i) =>
                i === idx ? { ...it, reasoning: lastLlmReason.reasoning + delta } : it,
              ),
            };
          });
        }
      }
    }
  }, []);

  // ---- 整包事件 → timeline（assistant 打字机 + 子 Agent live + 拉新触发）----
  const loadPendingApproval = useCallback(
    async (sid: number) => {
      try {
        const vo = await api.pendingToolApproval(sid);
        if (!vo?.approvalId || !vo?.toolCallsJson) {
          setToolApproval(null);
          return;
        }
        const batch = parseApproval(vo);
        setToolApproval(batch);
      } catch {
        // keep current drawer on transient errors
      }
    },
    [api],
  );

  const loadPendingClarifications = useCallback(
    async (sid: number) => {
      try {
        const rows = await api.pendingClarifications(sid);
        const pending = (rows || []).filter(
          (row) => row?.clarificationId && row?.type !== 'tool_approval',
        );
        if (pending.length === 0) {
          setClarification(null);
          return;
        }
        setClarification({
          runId: Number(pending[0].runId),
          items: pending.map((row) => ({
            clarificationId: String(row.clarificationId),
            runId: Number(row.runId),
            title: row.title,
            type: row.type,
            multiple: row.multiple === true,
            options: row.options,
          })),
        });
      } catch {
        // keep current drawer on transient errors
      }
    },
    [api],
  );

  const handleSseEvent = useCallback(
    (parsed: Record<string, unknown>) => {
      const evtType = String(parsed.eventType || parsed.type || '');
      const d = (parsed.data ?? parsed) as Record<string, any>;
      const sid = sessionIdRef.current;
      if (sid == null || !Number.isFinite(sid)) return;

      if (evtType === 'TOOL_APPROVAL_PENDING') {
        const batch = parseApproval(d);
        if (batch) setToolApproval(batch);
        void refreshLatest(sid);
        return;
      }
      if (evtType === 'TOOL_APPROVAL_RESPONDED' || evtType === 'TOOL_APPROVAL_DISMISSED') {
        setToolApproval((prev) =>
          prev && prev.approvalId === String(d?.approvalId) ? null : prev,
        );
        void loadPendingApproval(sid);
        void refreshLatest(sid);
        return;
      }

      // 子 Agent 实时：纯 SSE 聚合 live + 头行占位
      if (d?.subAgentRunId != null) {
        handleSubAgentLiveEvent(evtType, d);
        const liveSubId = Number(d.subAgentRunId);
        if (Number.isFinite(liveSubId)) {
          setRows((prev) => {
            const exists = prev.some(
              (r) =>
                r.kind === 'subagent' &&
                Number(r.refSubAgentRunId) === liveSubId,
            );
            if (exists) return prev;
            const displayName = d?.displayNameCn || d?.displayName || '子 Agent';
            return [
              ...prev,
              {
                seq: placeholderSeq(liveSubId),
                kind: 'subagent',
                refSubAgentRunId: liveSubId,
                state: 'RUNNING',
                title: displayName,
                content: {
                  displayName,
                  state: 'RUNNING',
                  parentToolCallId: d?.parentToolCallId ?? null,
                },
              },
            ].sort((a, b) => a.seq - b.seq);
          });
        }
      }

      const tlSubType = String(d?.reasoningSubType || d?.status || evtType);

      // 上下文压缩：RUNNING 本地插行（后端已回填 seq/kind），输出 token 追加到正文
      if (d?.seq != null && d?.kind === 'compaction') {
        const seq = Number(d.seq);
        const delta = evtType === 'content_token' ? String(d?.response || '') : '';
        const terminal =
          tlSubType === 'compaction_completed' ||
          tlSubType === 'compaction_failed';
        setRows((prev) => {
          const m = new Map<number, TimelineRow>(prev.map((r) => [r.seq, r]));
          const existing = m.get(seq);
          const content = { ...((existing?.content || {}) as TimelineRow['content']) };
          if (delta) content.text = (content.text || '') + delta;
          m.set(seq, {
            ...(existing || {}),
            seq,
            kind: 'compaction',
            state: terminal
              ? tlSubType === 'compaction_failed'
                ? 'FAILED'
                : 'COMPLETED'
              : 'RUNNING',
            title:
              d?.assistantReply || existing?.title || '正在压缩上下文…',
            content,
          });
          return [...m.values()].sort((a, b) => a.seq - b.seq);
        });
      }

      // assistant 容器打字机：seq+kind+response 就地追加 reply/thinking
      if (
        d?.seq != null &&
        d?.kind === 'assistant' &&
        d?.response &&
        (evtType === 'content_token' || evtType === 'reasoning_token')
      ) {
        const field = evtType === 'content_token' ? 'reply' : 'thinking';
        const delta = String(d.response);
        setRows((prev) => {
          const m = new Map<number, TimelineRow>(prev.map((r) => [r.seq, r]));
          const existing = m.get(Number(d.seq));
          if (existing) {
            m.set(Number(d.seq), {
              ...existing,
              content: {
                ...existing.content,
                [field]: (existing.content[field] || '') + delta,
              },
            });
          } else {
            m.set(Number(d.seq), {
              seq: Number(d.seq),
              kind: 'assistant',
              state: 'RUNNING',
              content: { [field]: delta },
            });
          }
          return [...m.values()].sort((a, b) => a.seq - b.seq);
        });
      }

      // 终态/工具结束/澄清 → 按 afterSeq 补行（权威合并）

      // 事件驱动 runActive：run_running/主助手打字机=在跑；终态=结束
      if (tlSubType === 'run_running') {
        setRunActiveState(true);
      } else if (tlSubType === 'run_waiting') {
        // wait 挂起期间 run 仍在进行，到点自行恢复 —— 保持锁定。
        // 断线重连正好落在等待窗口时靠这条把 runActive 重新立起来。
        setRunActiveState(true);
      } else if (
        (RUN_TERMINAL_SUB_TYPES as readonly string[]).includes(tlSubType)
      ) {
        setRunActiveState(false);
      } else if (
        (evtType === 'content_token' || evtType === 'reasoning_token') &&
        d?.seq != null &&
        d?.kind === 'assistant'
      ) {
        // 澄清续跑等场景可能不重发 run_running，用主助手打字机兜底
        setRunActiveState(true);
      }

      // 澄清：本地就地更新 + 记录已应答（防 refreshLatest 回退）
      if (String(evtType).startsWith('clarification_')) {
        void loadPendingClarifications(sid);
        if (evtType === 'clarification_responded') {
          const respondedKey = clarificationKey(d?.runId, d?.clarificationId);
          if (respondedKey) answeredClarificationKeysRef.current.add(respondedKey);
          setRows((prev) =>
            prev.map((r) => {
              if (r.kind !== 'clarification') return r;
              if (Number(r.runId) !== Number(d?.runId)) return r;
              // 有 id 必须命中同一轮；无 id 时只改仍 PENDING 的行，避免连带改写
              // 同 run 续跑产生的后续澄清。
              const wantId = d?.clarificationId;
              const haveId = r.content?.clarificationId;
              if (wantId != null && haveId != null) {
                if (String(haveId) !== String(wantId)) return r;
              } else if (r.state !== 'PENDING') {
                return r;
              }
              return {
                ...r,
                state: 'ANSWERED',
                content: {
                  ...r.content,
                  response: d?.response ?? r.content.response,
                },
              };
            }),
          );
        } else if (evtType === 'clarification_dismissed') {
          setRows((prev) =>
            prev.map((r) =>
              r.kind === 'clarification' &&
              Number(r.runId) === Number(d?.runId) &&
              r.state === 'PENDING'
                ? { ...r, state: 'CANCELLED' }
                : r,
            ),
          );
        }
      }

      if (tlSubType === 'run_awaiting_user') {
        void loadPendingApproval(sid);
        void loadPendingClarifications(sid);
      }

      if (
        tlSubType === 'tool_call_succeeded' ||
        tlSubType === 'tool_call_failed'
      ) {
        // 工具事件：整页覆盖刷新（补迟到落库的截图 images 等），避免 afterSeq 增量跳过既有工具行
        void refreshTail(sid);
      } else if (
        PULL_TRIGGER_SUB_TYPES.includes(tlSubType) ||
        String(evtType).startsWith('clarification_')
      ) {
        void refreshLatest(sid);
      }
    },
    [handleSubAgentLiveEvent, refreshLatest, refreshTail, loadPendingApproval, loadPendingClarifications],
  );

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current != null) {
      window.clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
  }, []);

  // 建立/重建会话 SSE；断线按指数退避重连（≤30s）；仅当仍是当前会话时重连。
  const openStream = useCallback(
    (sessionId: number, token: string, apiBase: string) => {
      if (abortRef.current) abortRef.current.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      const base = String(apiBase ?? '/api/v1').replace(/\/+$/, '');
      const scheduleReconnect = () => {
        if (sessionIdRef.current !== sessionId) return;
        clearReconnectTimer();
        const attempt = reconnectAttemptRef.current++;
        const delay = Math.min(1000 * 2 ** attempt, 30000);
        reconnectTimerRef.current = window.setTimeout(() => {
          reconnectTimerRef.current = null;
          if (sessionIdRef.current !== sessionId) return;
          openStream(sessionId, token, apiBase);
        }, delay);
      };
      void connectSse(
        `${base}/runtime/${sessionId}/stream`,
        token,
        {
          onOpen: () => {
            reconnectAttemptRef.current = 0;
            clearReconnectTimer();
            void loadPendingApproval(sessionId);
            void loadPendingClarifications(sessionId);
          },
          onMessage: (payload: string) => {
            try {
              const parsed = JSON.parse(payload) as Record<string, unknown>;
              handleSseEvent(parsed);
            } catch {
              // 忽略非 JSON 行（注释/心跳）
            }
          },
          onError: () => {
            scheduleReconnect();
          },
        },
        controller.signal,
      ).catch(() => scheduleReconnect());
    },
    [clearReconnectTimer, handleSseEvent, loadPendingApproval, loadPendingClarifications],
  );

  // 会话切换即重建：先断开旧流、清空状态；由 CopilotView 在选会话时调用 connect()。
  const connect = useCallback(
    (sessionId: number, token: string, apiBase: string) => {
      clearReconnectTimer();
      reconnectAttemptRef.current = 0;
      if (abortRef.current) abortRef.current.abort();
      sessionIdRef.current = sessionId;
      cursorsRef.current = { oldestSeq: null, newestSeq: null };
      pendingSeqRef.current = 0;
      answeredClarificationKeysRef.current.clear();
      setRows([]);
      setSubAgentLiveMap({});
      setPendingUserRows([]);
      setRunActiveState(false);
      setHasMore(false);
      setLoadingOlder(false);
      setToolApproval(null);
      setClarification(null);

      void loadInitial(sessionId);
      void loadPendingApproval(sessionId);
      void loadPendingClarifications(sessionId);
      openStream(sessionId, token, apiBase);
    },
    [clearReconnectTimer, loadInitial, loadPendingApproval, loadPendingClarifications, openStream],
  );

  /** 乐观提交澄清后立即登记已应答，防止 refreshLatest 用旧 PENDING 回退。
   *  键必须是 runId+clarificationId：同 run 续跑会复用 runId，只按 runId 会误伤后续澄清。 */
  const markClarificationAnswered = useCallback((runId: number, clarificationId?: string | null) => {
    const key = clarificationKey(runId, clarificationId);
    if (key) answeredClarificationKeysRef.current.add(key);
  }, []);

  // 卸载时断开连接并清理重连定时器
  useEffect(() => {
    return () => {
      clearReconnectTimer();
      if (abortRef.current) abortRef.current.abort();
    };
  }, [clearReconnectTimer]);

  const loadSubAgentSteps = useCallback(
    (subAgentRunId: number) => api.subAgentTimeline(subAgentRunId),
    [api],
  );

  const resolveToolApproval = useCallback(
    async (decisions: Array<{ callId: string; decision: string }>) => {
      const sid = sessionIdRef.current;
      if (!sid || !toolApproval) return;
      await api.resolveToolApproval(sid, toolApproval.runId, toolApproval.approvalId, decisions);
      setToolApproval(null);
      await loadPendingApproval(sid);
    },
    [api, toolApproval, loadPendingApproval],
  );

  const resolveClarifications = useCallback(
    async (answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>) => {
      const sid = sessionIdRef.current;
      if (!sid || !clarification) return;
      await api.resolveClarifications(sid, clarification.runId, answers);
      setClarification(null);
      await loadPendingClarifications(sid);
      void refreshLatest(sid);
    },
    [api, clarification, loadPendingClarifications, refreshLatest],
  );

  return {
    rows,
    hasMore,
    loadingOlder,
    subAgentLiveMap,
    pendingUserRows,
    runActive,
    loadInitial,
    loadOlder,
    refreshLatest,
    refreshTail,
    addUserMessage,
    removeUserMessage,
    markRunInactive,
    handleSseEvent,
    loadSubAgentSteps,
    connect,
    markClarificationAnswered,
    toolApproval,
    resolveToolApproval,
    clarification,
    resolveClarifications,
  };
}
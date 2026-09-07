import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiClient, TimelineQuery } from './api';
import { connectSse } from './sse';
import {
  fetchInitialTimeline,
  fetchLatestTimeline,
  fetchOlderTimeline,
  mergeTimeline,
  type TimelineCursors,
} from './timeline';
import type { SubAgentTimelineItemVO, TimelineRow } from './types';

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
    };

export type SubAgentLiveMap = Record<number, SubAgentLiveStep[]>;

const PULL_TRIGGER_SUB_TYPES = [
  'run_completed',
  'run_failed',
  'run_cancelled',
  'run_awaiting_user',
  'tool_call_succeeded',
  'tool_call_failed',
];

interface TimelineStream {
  rows: TimelineRow[];
  hasMore: boolean;
  loadingOlder: boolean;
  subAgentLiveMap: SubAgentLiveMap;
  loadInitial: (sessionId: number) => Promise<void>;
  loadOlder: (sessionId: number) => Promise<void>;
  refreshLatest: (sessionId: number) => Promise<void>;
  /** 整包 SSE 事件（RuntimeEventVO {eventType, data}）→ timeline 打字机/子 Agent live/拉新。 */
  handleSseEvent: (parsed: Record<string, unknown>) => void;
  /** 子 Agent 权威时间线拉取（终态校正）。 */
  loadSubAgentSteps: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  /** 建立会话 SSE 连接（会话切换时调用）。 */
  connect: (sessionId: number, token: string) => void;
}

/** 子 Agent LIVE 占位行 seq：负且按 subAgentRunId 唯一（避免同 seq 冲突）。 */
function placeholderSeq(subAgentRunId: number): number {
  return -(Math.abs(subAgentRunId) + 1);
}

export function useTimelineStream(api: ApiClient): TimelineStream {
  const [rows, setRows] = useState<TimelineRow[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [subAgentLiveMap, setSubAgentLiveMap] = useState<SubAgentLiveMap>({});
  const cursorsRef = useRef<TimelineCursors>({ oldestSeq: null, newestSeq: null });
  const sessionIdRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const getPage = useCallback(
    (sessionId: number) => (q: TimelineQuery) =>
      api.getTimeline(sessionId, q),
    [api],
  );

  const loadInitial = useCallback(
    async (sessionId: number) => {
      try {
        const page = getPage(sessionId);
        const { rows: pageRows, hasMore: m, cursors } =
          await fetchInitialTimeline(page, 10);
        cursorsRef.current = cursors;
        setRows(pageRows);
        setHasMore(m);
      } catch {
        // 加载失败保持空态，SSE 终态事件仍会触发 refreshLatest 尝试补
      }
    },
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
        setRows((prev) => mergeTimeline(prev, older));
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
        setRows((prev) => mergeTimeline(prev, fresh));
      } catch {
        // 补拉失败忽略（SSE 打字机仍会就地更新）
      }
    },
    [getPage],
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
          const nl = delta.indexOf('\n');
          const body = nl >= 0 ? delta.slice(nl + 1) : delta;
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
  const handleSseEvent = useCallback(
    (parsed: Record<string, unknown>) => {
      const evtType = String(parsed.eventType || parsed.type || '');
      const d = (parsed.data ?? parsed) as Record<string, any>;
      const sid = sessionIdRef.current;
      if (sid == null || !Number.isFinite(sid)) return;

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
                content: { displayName, state: 'RUNNING' },
              },
            ].sort((a, b) => a.seq - b.seq);
          });
        }
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
      const tlSubType = String(d?.reasoningSubType || d?.status || evtType);
      if (
        PULL_TRIGGER_SUB_TYPES.includes(tlSubType) ||
        String(evtType).startsWith('clarification_')
      ) {
        void refreshLatest(sid);
      }
    },
    [handleSubAgentLiveEvent, refreshLatest],
  );

  // 会话切换即重建：先断开旧流、清空状态；由 CopilotView 在选会话时调用 connect()。
  const connect = useCallback(
    (sessionId: number, token: string) => {
      if (abortRef.current) abortRef.current.abort();
      sessionIdRef.current = sessionId;
      cursorsRef.current = { oldestSeq: null, newestSeq: null };
      setRows([]);
      setSubAgentLiveMap({});
      setHasMore(false);
      setLoadingOlder(false);

      void loadInitial(sessionId);

      const controller = new AbortController();
      abortRef.current = controller;
      void connectSse(
        `/api/v1/runtime/${sessionId}/stream`,
        token,
        {
          onOpen: () => {},
          onMessage: (payload: string) => {
            try {
              const parsed = JSON.parse(payload) as Record<string, unknown>;
              handleSseEvent(parsed);
            } catch {
              // 忽略非 JSON 行（注释/心跳）
            }
          },
          onError: () => {
            // 断线不自动重连：由上层（CopilotView）按需重建
          },
        },
        controller.signal,
      );
    },
    [handleSseEvent, loadInitial],
  );

  // 卸载时断开
  useEffect(() => {
    return () => {
      if (abortRef.current) abortRef.current.abort();
    };
  }, []);

  const loadSubAgentSteps = useCallback(
    (subAgentRunId: number) => api.subAgentTimeline(subAgentRunId),
    [api],
  );

  return {
    rows,
    hasMore,
    loadingOlder,
    subAgentLiveMap,
    loadInitial,
    loadOlder,
    refreshLatest,
    handleSseEvent,
    loadSubAgentSteps,
    connect,
  };
}
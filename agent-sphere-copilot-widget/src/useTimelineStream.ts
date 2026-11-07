import { TIMING } from './constants';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiClient, RuntimeStateVO, TimelineQuery } from './api';
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

/** Sub-agent live steps (pure SSE aggregation; the terminal state is corrected by a one-off history fetch). */
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
      /** Browser screenshot references in the tool result ([{fileKey, contentType}]), replayed by live steps. */
      images?: { fileKey: string; contentType?: string }[];
    };

export type SubAgentLiveMap = Record<number, SubAgentLiveStep[]>;

const PULL_TRIGGER_SUB_TYPES = [
  'run_running',
  'run_completed',
  'run_failed',
  'run_cancelled',
  'run_awaiting_user',
  // wait suspension: the same run resumes by itself when the time comes, so fetch once to show "waiting".
  // Deliberately not in RUN_TERMINAL_SUB_TYPES - it is not terminal; including it would unlock the input
  // during the wait, effectively reporting "waiting" as "task finished".
  'run_waiting',
  'tool_call_succeeded',
  'tool_call_failed',
  // Compaction terminal state: fetch the authoritative row once (body comes from summary_after). Not for running - a shallow merge would overwrite the streamed body.
  'compaction_completed',
  'compaction_failed',
];

/** Parse browser screenshot references from the tool result artifact JSON (data.screenshot.fileKey/contentType). */
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

/** run terminal sub-types: arriving means the current run is over (unlock input / restore the send button). */
const RUN_TERMINAL_SUB_TYPES = [
  'run_completed',
  'run_failed',
  'run_cancelled',
  'run_awaiting_user',
] as const;

/** run status events: the run_status row is updated in place, so refreshes must use the tail window (afterSeq would skip the same seq). */
const RUN_STATUS_SUB_TYPES = [
  'run_completed',
  'run_failed',
  'run_cancelled',
  'run_awaiting_user',
  'run_waiting',
] as const;

interface TimelineStream {
  rows: TimelineRow[];
  hasMore: boolean;
  loadingOlder: boolean;
  subAgentLiveMap: SubAgentLiveMap;
  /** Optimistically inserted user message not yet replaced by the backend's authoritative row (seq<0, rendered at the end of the timeline). */
  pendingUserRows: TimelineRow[];
  /** Event-driven "is a run currently active in this session" (run_running/typewriter=true; terminal=false). */
  runActive: boolean;
  /** Authoritative runtime value (aggregated from source tables): the single source for the send lock / pending checks. */
  runtimeState: RuntimeStateVO | null;
  /** Instant hint while tool arguments are still streaming in. */
  preparingTool: { publishId?: string; name?: string } | null;
  loadInitial: (sessionId: number) => Promise<void>;
  loadOlder: (sessionId: number) => Promise<void>;
  refreshLatest: (sessionId: number) => Promise<void>;
  /** Full-page overwrite refresh after tool events (to pick up late-landing screenshot images and the like). */
  refreshTail: (sessionId: number) => Promise<void>;
  /** User message being propagated: not persisted yet, shown locally first and removed when the authoritative row arrives; also sets runActive=true (input locks on click). */
  addUserMessage: (text: string, images?: { fileKey: string; contentType?: string }[]) => void;
  removeUserMessage: (text: string) => void;
  /** Manually reset runActive on scenarios such as send failure. */
  markRunInactive: () => void;
  /** Whole SSE event (RuntimeEventVO {eventType, data}) -> timeline typewriter / sub-agent live / refresh. */
  handleSseEvent: (parsed: Record<string, unknown>) => void;
  /** Fetch the authoritative sub-agent timeline (terminal-state correction). */
  loadSubAgentSteps: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  /** Open the session SSE connection (called when the session changes); apiBase is needed to build the right stream URL (same-origin relative or cross-origin absolute). */
  connect: (sessionId: number, token: string, apiBase: string) => void;
  /** Record a clarification as answered after an optimistic submit (prevents refreshLatest from rolling back the optimistic state). */
  markClarificationAnswered: (runId: number, clarificationId?: string | null) => void;
  /** L2 tool approval batch shown in the bottom drawer. */
  toolApproval: ToolApprovalBatch | null;
  resolveToolApproval: (decisions: Array<{ callId: string; decision: string }>) => Promise<void>;
  clarification: ClarificationBatch | null;
  resolveClarifications: (
    answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
  ) => Promise<void>;
}

/** Sub-agent LIVE placeholder row seq: negative and unique per subAgentRunId (avoids seq collisions). */
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
  const [runtimeState, setRuntimeState] = useState<RuntimeStateVO | null>(null);
  const [preparingTool, setPreparingTool] = useState<{
    publishId?: string;
    name?: string;
  } | null>(null);
  const [toolApproval, setToolApproval] = useState<ToolApprovalBatch | null>(null);
  const [clarification, setClarification] = useState<ClarificationBatch | null>(null);
  const pendingSeqRef = useRef(0);
  const cursorsRef = useRef<TimelineCursors>({ oldestSeq: null, newestSeq: null });
  const sessionIdRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  /** runIds of locally answered clarifications: prevents refreshLatest from rolling back the optimistic state with a stale server PENDING. */
  const answeredClarificationKeysRef = useRef<Set<string>>(new Set());
  const reconnectAttemptRef = useRef(0);
  const reconnectTimerRef = useRef<number | null>(null);

  const getPage = useCallback(
    (sessionId: number) => (q: TimelineQuery) =>
      api.getTimeline(sessionId, q),
    [api],
  );

  /** Remove text-matched optimistic user rows once the authoritative row arrives (avoids duplicate bubbles). */
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
      // Use a non-empty placeholder text for image-only sends, to stay compatible with reconcile's existing text-based dedup (replaced when the authoritative row arrives)
      const displayText = text.trim() ? text : '[Image]';
      setPendingUserRows((prev) => [
        ...prev,
        {
          seq,
          kind: 'user',
          state: 'COMPLETED',
          content: { text: displayText, ...(images && images.length ? { images } : {}) },
        },
      ]);
      // Lock the input on send (no need to wait for run_running) - the "instant" part of event-driven locking
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

  /** Authoritative runtime refresh: reads only /runtime/{sid}/state, does not scan the timeline. */
  const refreshRuntimeState = useCallback(
    async (sessionId: number) => {
      try {
        const state = await api.getRuntimeState(sessionId);
        if (sessionIdRef.current !== null && sessionIdRef.current !== sessionId) {
          return;
        }
        setRuntimeState(state);
        setRunActiveState(state.blockReasons?.includes('RUNNING') ?? false);
      } catch {
        // No fallback: errors go to the global request handler; keep the previous authoritative value
      }
    },
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
        reconcilePendingUserRows(pageRows);
        // Single source for the send lock: the runtime endpoint (not the timeline's run_status)
        void refreshRuntimeState(sessionId);
      } catch {
        // Keep the empty state when loading fails; terminal SSE events will still trigger refreshLatest to catch up
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
        // Keep the current state when paging fails
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
        // Ignore catch-up failures (the SSE typewriter still updates in place)
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
          // Update the cursor: the tail window counts as the authoritative latest and advances afterSeq
          updateCursors(cursorsRef.current, {
            rows: fresh,
            hasMore: false,
            oldestSeq: null,
            newestSeq: null,
          });
        }
        reconcilePendingUserRows(fresh);
      } catch {
        // Ignore catch-up failures
      }
    },
    [getPage, reconcilePendingUserRows],
  );

  // ---- Sub-agent live step aggregation (ported from the main site's handleSubAgentLiveEvent) ----
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
        // Tool result contains browser screenshots -> carry images on the live step directly (consistent with the backend timeline after refresh)
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
          // New LLM round: strip the first-frame sentinel line ("<marker>id: name\n"), marking the previous round as finished
          // Accept both old and new prefixes (`▶ Agent ` / `▶ Skill `) so historical sessions are not broken
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

  // ---- Whole events -> timeline (assistant typewriter + sub-agent live + refresh triggers) ----
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
            description: row.description,
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

      // Sub-agent live: pure SSE aggregation for live + header-row placeholder
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
            const displayName = d?.displayNameCn || d?.displayName || 'Sub-agent';
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

      if (tlSubType === 'tool_input_preparing') {
        setPreparingTool({
          publishId: d?.publishId ? String(d.publishId) : undefined,
          name:
            d?.displayNameCn ||
            d?.displayNameEn ||
            d?.toolName ||
            'Tool',
        });
      } else if (
        tlSubType === 'tool_call_started' ||
        tlSubType === 'tool_call_in_progress' ||
        tlSubType === 'run_pending' ||
        tlSubType === 'tool_call_succeeded' ||
        tlSubType === 'tool_call_failed' ||
        tlSubType === 'run_completed' ||
        tlSubType === 'run_failed' ||
        tlSubType === 'run_cancelled' ||
        tlSubType === 'run_awaiting_user'
      ) {
        setPreparingTool(null);
      }

      // Context compaction: insert a local RUNNING row (the backend has already filled in seq/kind), append output tokens to the body
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
              d?.statusTitle ||
              d?.assistantReply ||
              existing?.title ||
              'Compacting context…',
            content,
          });
          return [...m.values()].sort((a, b) => a.seq - b.seq);
        });
      }

      // assistant container typewriter: append reply/thinking in place by seq+kind+response
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

      // A run_status row is "one row per run, updated in place": the event already carries seq/status/title,
      // so merge it in place directly, avoiding the case where an afterSeq delta cannot fetch a same-seq update (retry count not refreshed).
      if (d?.seq != null && d?.kind === 'run_status') {
        const rsSeq = Number(d.seq);
        const rsState = d?.status || (tlSubType === 'run_waiting' ? 'WAITING' : null);
        const rsText =
          d?.statusTitle || d?.assistantReply || d?.response || '';
        const row: TimelineRow = {
          seq: rsSeq,
          runId: d?.runId != null ? Number(d.runId) : null,
          kind: 'run_status',
          state: rsState,
          subtype: d?.waitKind === 'LLM_RETRY' ? 'LLM_RETRY' : (rsState ?? undefined),
          title: rsText,
          content: { text: rsText, state: rsState ?? undefined },
        };
        setRows((prev) => mergeTimeline(prev, [row], answeredClarificationKeysRef.current));
      }

      // Terminal/tool end/clarification -> catch up rows by afterSeq (authoritative merge)

      // Event-driven runActive: run_running/main-assistant typewriter=running; terminal=finished
      if (tlSubType === 'run_running') {
        setRunActiveState(true);
      } else if (tlSubType === 'run_waiting') {
        // During a wait suspension the run is still active and resumes by itself when the time comes - keep it locked.
        // When a reconnect lands exactly in the wait window, this restores runActive.
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
        // Scenarios such as clarification resumption may not resend run_running; fall back to the main-assistant typewriter
        setRunActiveState(true);
      }
      // Authoritative correction: refresh runtime state after run / clarification / approval events
      if (
        sid != null &&
        (String(tlSubType).startsWith('run_') ||
          String(evtType).startsWith('clarification_') ||
          String(evtType).startsWith('tool_approval') ||
          String(evtType) === 'TOOL_APPROVAL_PENDING')
      ) {
        void refreshRuntimeState(sid);
      }

      // Clarification: update locally in place + record it as answered (prevents refreshLatest rollback)
      if (String(evtType).startsWith('clarification_')) {
        if (sid != null) void refreshRuntimeState(sid);
        void loadPendingClarifications(sid);
        if (evtType === 'clarification_responded') {
          const respondedKey = clarificationKey(d?.runId, d?.clarificationId);
          if (respondedKey) answeredClarificationKeysRef.current.add(respondedKey);
          setRows((prev) =>
            prev.map((r) => {
              if (r.kind !== 'clarification') return r;
              if (Number(r.runId) !== Number(d?.runId)) return r;
              // With an id it must match the same round; without an id, only rewrite rows still PENDING, to avoid also rewriting
              // subsequent clarifications produced by continuing the same run.
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
        tlSubType === 'tool_call_failed' ||
        // run_status updates the same row in place: afterSeq cannot fetch it, so a tail-window overwrite is required
        (RUN_STATUS_SUB_TYPES as readonly string[]).includes(tlSubType)
      ) {
        // Tool events: full-page overwrite refresh (to pick up late-landing screenshot images etc.), avoiding afterSeq deltas skipping existing tool rows
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

  // Open/rebuild the session SSE; reconnect with exponential backoff (<=30s); only reconnect while it is still the current session.
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
        const delay = Math.min(TIMING.SSE_RECONNECT_BASE_MS * 2 ** attempt, TIMING.SSE_RECONNECT_MAX_MS);
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
              // Ignore non-JSON lines (comments/heartbeats)
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

  // Rebuild on session switch: disconnect the old stream and clear state first; CopilotView calls connect() when the session is selected.
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
      setPreparingTool(null);
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

  /** Record a clarification as answered right after an optimistic submit, preventing refreshLatest from rolling back with a stale PENDING.
   *  The key must be runId+clarificationId: continuing the same run reuses runId, and runId alone would affect later clarifications. */
  const markClarificationAnswered = useCallback((runId: number, clarificationId?: string | null) => {
    const key = clarificationKey(runId, clarificationId);
    if (key) answeredClarificationKeysRef.current.add(key);
  }, []);

  // Disconnect and clear the reconnect timer on unmount
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
    runtimeState,
    preparingTool,
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
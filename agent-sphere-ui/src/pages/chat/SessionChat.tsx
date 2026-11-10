import { LeftOutlined, RightOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  history,
  useIntl,
  useLocation,
  useNavigate,
  useParams,
} from '@umijs/max';
import { App } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import SetModelRouteModal from '@/components/SetModelRouteModal';
import { API_PATHS } from '@/constants/endpoints';
import { PERM } from '@/constants/permissions';
import { STATUS } from '@/constants/status';
import { TIMELINE_STATE } from '@/constants/timeline';
import { TIMING } from '@/constants/timing';
import {
  DOCWRITE_TOOL_NAME,
  TODOWRITE_TOOL_NAME,
  TOOL_CALL_RECORD_STATUS,
} from '@/constants/toolCall';
import { useCan } from '@/hooks/usePermission';
import { agentApi } from '@/services/agentSphere/api';
import { getToken } from '@/utils/auth';
import { localizeApiError } from '@/utils/errorMessage';
import { connectSse } from '@/utils/sse';
import type { ClarificationBatch } from './components/ClarificationDrawer';
import ChatMain from './components/chat';
import { stripSubAgentSentinel } from './components/chat/subAgentMarker';
import type { SubAgentLiveMap } from './components/chat/subAgentTypes';
import ExpandModal from './components/ExpandModal';
import InstanceDrawer from './components/InstanceDrawer';
import Landing from './components/Landing';
import SessionPanel from './components/SessionPanel';
import Sidebar from './components/Sidebar';
import type { ToolApprovalBatch } from './components/ToolApprovalDrawer';
import { SESSION_USAGE_REFRESH_EVENT } from './components/Usage';
import { useStyles } from './style';
import { createCoalescedTask } from './utils/coalesce';

const SESSION_PAGE_SIZE = 10;

type ConversationScope = 'USER' | 'GROUP' | 'TENANT';
type UserSessionRole = 'USER' | 'ROUTER' | 'WORKER';

const CHAT_SCOPE_STORAGE_KEY = 'agent-sphere.chat.scope';
const CHAT_ROLE_STORAGE_KEY = 'agent-sphere.chat.role';
const CONVERSATION_SCOPES: ConversationScope[] = ['USER', 'GROUP', 'TENANT'];
const USER_SESSION_ROLES: UserSessionRole[] = ['USER', 'ROUTER', 'WORKER'];

function readStoredScope(): ConversationScope {
  if (typeof window === 'undefined') return 'USER';
  const stored = window.sessionStorage.getItem(CHAT_SCOPE_STORAGE_KEY);
  return CONVERSATION_SCOPES.includes(stored as ConversationScope)
    ? (stored as ConversationScope)
    : 'USER';
}

function readStoredRole(): UserSessionRole {
  if (typeof window === 'undefined') return 'USER';
  const stored = window.sessionStorage.getItem(CHAT_ROLE_STORAGE_KEY);
  return USER_SESSION_ROLES.includes(stored as UserSessionRole)
    ? (stored as UserSessionRole)
    : 'USER';
}

function persistChatPreference(key: string, value: string) {
  if (typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(key, value);
  } catch {
    // Storage can be unavailable (private mode); the current tab still works in memory.
  }
}

/** Internal sessions (router / worker / group router) are view-only. */
const READ_ONLY_SESSION_TYPES = new Set(['ROUTER', 'WORKER', 'GROUP_ROUTER']);

/**
 * Local guard key for the optimistic clarification state. It must include clarificationId: when
 * the same run resumes, the backend reuses runId, while each clarification round has a different
 * clarificationId. Using runId alone would misjudge "the second clarification" as already answered.
 * When clarificationId is missing, return null — callers then [drop the guard] and trust the
 * server state, because the cost of wrongly blocking (a permanently wrong state until refresh) is
 * far greater than letting one race fall through.
 */
function clarificationKey(
  runId: unknown,
  clarificationId: unknown,
): string | null {
  if (runId == null || clarificationId == null || clarificationId === '')
    return null;
  return `${runId}:${clarificationId}`;
}

/** Tolerate the backend's `YYYY-MM-DD HH:mm:ss[.ffffff]` (non-ISO) and ISO formats → timestamp; 0 on failure. */
function toTs(v: any): number {
  if (typeof v === 'number') return v;
  if (typeof v !== 'string' || !v) return 0;
  const iso = v.replace(' ', 'T');
  const ts = new Date(iso).getTime();
  if (Number.isFinite(ts)) return ts;
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/.exec(v);
  if (!m) return 0;
  const [, y, mo, d, h, mi, s] = m.map(Number);
  return new Date(y, mo - 1, d, h, mi, s).getTime();
}

export interface SessionChatProps {
  /** Training/embedded host passes the session id explicitly; the console uses the route param. */
  sessionId?: number;
  /** Hide the console sidebar and fit the chat into an embedded host. */
  trainingMode?: boolean;
  /** Notifies the host when the runtime SSE connects or drops. */
  onSseStatusChange?: (connected: boolean) => void;
}

export function SessionChat(props: SessionChatProps = {}) {
  const { sessionId: routeSessionId } = useParams();
  const sessionId =
    props.sessionId != null ? String(props.sessionId) : routeSessionId;
  const trainingMode = Boolean(props.trainingMode);
  const navigate = useNavigate();
  const location = useLocation();
  const intl = useIntl();
  const { message, modal } = App.useApp();
  const { styles } = useStyles();

  const [sessions, setSessions] = useState<any[]>([]);
  const [sessionOffset, setSessionOffset] = useState(0);
  const [hasMoreSessions, setHasMoreSessions] = useState(true);
  const canViewTenant = useCan(PERM.instanceTenant);
  const [conversationScope, setConversationScope] = useState<ConversationScope>(
    () => {
      const stored = readStoredScope();
      return stored === 'TENANT' && !canViewTenant ? 'USER' : stored;
    },
  );
  const [userRole, setUserRole] = useState<UserSessionRole>(readStoredRole);
  useEffect(() => {
    if (conversationScope === 'TENANT' && !canViewTenant) {
      persistChatPreference(CHAT_SCOPE_STORAGE_KEY, 'USER');
      setConversationScope('USER');
    }
  }, [canViewTenant, conversationScope]);
  const [currentSession, setCurrentSession] = useState<any>(null);
  const currentSessionRef = useRef<any>(null);
  const sessionsRef = useRef<any[]>([]);
  currentSessionRef.current = currentSession;
  sessionsRef.current = sessions;

  /** Internal/foreign sessions only load read-only data; never call owner-only runtime endpoints. */
  const isReadOnlySessionId = useCallback((id?: number | null) => {
    if (id == null) return false;
    const session =
      currentSessionRef.current?.id === id
        ? currentSessionRef.current
        : sessionsRef.current.find((item: any) => item.id === id);
    return (
      !!session &&
      (session.readOnly ?? READ_ONLY_SESSION_TYPES.has(session.sessionType))
    );
  }, []);
  const [messages, setMessages] = useState<any[]>([]);
  const [timeline, setTimeline] = useState<any[]>([]);
  const [timelineHasMore, setTimelineHasMore] = useState(false);
  const [subAgentLive, setSubAgentLive] = useState<SubAgentLiveMap>({});
  const [preparingTool, setPreparingTool] = useState<{
    publishId?: string;
    name?: string;
  } | null>(null);
  const oldestSeqRef = useRef<number | null>(null);
  const newestSeqRef = useRef<number | null>(null);
  const [inputValue, setInputValue] = useState('');
  const [sending, setSending] = useState(false);
  const [runtimeState, setRuntimeState] = useState<any>(null);
  const [hasMoreHistory, setHasMoreHistory] = useState(true);
  const [sseConnected, setSseConnected] = useState(false);
  const [sessionUsage, setSessionUsage] = useState<any>(null);
  const [toolStats, setToolStats] = useState<any>(null);
  const [queuedMessages, setQueuedMessages] = useState<any[]>([]);
  const [runMode, setRunMode] = useState<'WRITE' | 'READ_ONLY'>('WRITE');
  const [toolApprovalBatch, setToolApprovalBatch] =
    useState<ToolApprovalBatch | null>(null);
  const [toolApprovalSubmitting, setToolApprovalSubmitting] = useState(false);
  const [clarificationBatch, setClarificationBatch] =
    useState<ClarificationBatch | null>(null);
  const [clarificationSubmitting, setClarificationSubmitting] = useState(false);
  const [instances, setInstances] = useState<any[]>([]);
  const [chosenInstance, setChosenInstance] = useState('');
  const [currentInstanceObj, setCurrentInstanceObj] = useState<any>(null);
  const [modelRoutes, setModelRoutes] = useState<any[]>([]);
  const [selectedModelRouteId, setSelectedModelRouteId] = useState<
    number | undefined
  >(undefined);
  /** Reasoning effort capability (dynamic default + per-level feasibility). */
  const [reasoningCapability, setReasoningCapability] = useState<any>(null);
  const [reasoningEffort, setReasoningEffort] = useState<string | undefined>(
    undefined,
  );
  /** Whether the user manually changed the level: do not overwrite the manual choice on idle refresh. */
  const reasoningTouchedRef = useRef(false);
  const [expandOpen, setExpandOpen] = useState(false);
  const [expandText, setExpandText] = useState('');
  const [instanceDrawerOpen, setInstanceDrawerOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [configModelInstance, setConfigModelInstance] = useState<any>(null);
  const [todos, setTodos] = useState<any[]>([]);
  const [toolCalls, setToolCalls] = useState<any[]>([]);
  const [sessionPanelOpen, setSessionPanelOpen] = useState(false);

  /** Reset the right pane when the user switches conversation scope / role. */
  const resetChatView = () => {
    navigate('/chat');
    setCurrentSession(null);
    setMessages([]);
    setTimeline([]);
    setTimelineHasMore(false);
    setSending(false);
    setRuntimeState(null);
    setToolApprovalBatch(null);
    setClarificationBatch(null);
    setQueuedMessages([]);
    setReasoningCapability(null);
    setReasoningEffort(undefined);
    setSelectedModelRouteId(undefined);
    setInstanceDrawerOpen(false);
    setSessionPanelOpen(false);
    setTodos([]);
    setToolCalls([]);
  };

  const historyPageRef = useRef(1);
  const abortRef = useRef<AbortController | null>(null);
  const currentSessionIdRef = useRef<number | null>(null);
  const reconnectCountRef = useRef(0);
  const sseStoppedRef = useRef(false);
  const reconnectTimerRef = useRef<number | null>(null);
  const runUserMessageRef = useRef<Map<number, string>>(new Map());
  const currentRunIdRef = useRef<number | null>(null);
  const stopFallbackRef = useRef<number | null>(null);
  const pendingMsgConsumedRef = useRef(false);
  // Clarifications already answered locally: prevents a subsequent refreshLatest from overwriting the optimistic state with the server's stale PENDING value.
  // The key must be runId + clarificationId, not runId alone — the backend's
  // resumeFromClarification [reuses the same runId] to resume while the run is AWAITING_USER, and
  // one session may ask the agent multiple rounds, each with a different clarificationId but a
  // shared runId. Keying by runId would mark every later clarification as "already answered" and
  // rewrite it to ANSWERED, so the options and input box would not render (only a refresh,
  // rebuilding the refs, recovers).
  const answeredClarificationsRef = useRef<Set<string>>(new Set());
  // Incremented when switching sessions: if an async request returns after the epoch changed, drop
  // it so data from the old session does not pollute the new one.
  const requestEpochRef = useRef(0);
  // Token streaming buffer: merge one setTimeline per frame instead of rebuilding the whole array on every token.
  const pendingDeltaRef = useRef(
    new Map<number, { reply?: string; thinking?: string }>(),
  );
  const deltaRafRef = useRef<number | null>(null);

  const loadSessions = useCallback(
    async (offset: number) => {
      try {
        const list = await agentApi.sessions.list(
          offset,
          SESSION_PAGE_SIZE,
          undefined,
          conversationScope,
          conversationScope === 'USER' ? userRole : undefined,
        );
        if (offset === 0) {
          setSessions(list);
        } else {
          setSessions((prev) => [...prev, ...list]);
        }
        setSessionOffset(offset + list.length);
        if (list.length < SESSION_PAGE_SIZE) setHasMoreSessions(false);
      } catch {}
    },
    [conversationScope, userRole],
  );

  useEffect(() => {
    agentApi.instances
      .listLatest(3)
      .then((data) => {
        const sorted = [...data].sort((a, b) => {
          if (a.modelRouteId && !b.modelRouteId) return -1;
          if (!a.modelRouteId && b.modelRouteId) return 1;
          return (
            new Date(b.updatedAt || b.createdAt).getTime() -
            new Date(a.updatedAt || a.createdAt).getTime()
          );
        });
        setInstances(sorted);
      })
      .catch(() => {});
    agentApi.routes
      .listAll()
      .then(setModelRoutes)
      .catch(() => {});
    if (!trainingMode) {
      setSessionOffset(0);
      setHasMoreSessions(true);
      loadSessions(0);
    }
  }, [loadSessions, trainingMode]);

  useEffect(() => {
    if (!chosenInstance && instances.length > 0) {
      const firstAvailable = instances.find((i: any) => i.modelRouteId);
      if (firstAvailable) {
        setChosenInstance(String(firstAvailable.id));
        setSelectedModelRouteId(firstAvailable.modelRouteId);
        setCurrentInstanceObj(firstAvailable);
      }
    }
  }, [instances, chosenInstance]);

  const loadHistory = useCallback(async (sid: number, page: number) => {
    try {
      const res = await agentApi.runs.listBySession(
        sid,
        page,
        page === 1 ? 10 : 3,
      );
      const runs = (res.records || []).slice().reverse();
      if (runs.length < (page === 1 ? 10 : 3)) setHasMoreHistory(false);
      historyPageRef.current = page + 1;

      const historyMsgs: any[] = [];
      for (const r of runs) {
        // Clarification-answer run: the reply is already shown in the clarification card, so skip a separate user bubble
        if (
          !r.clarificationResponse &&
          r.userMessage &&
          r.userMessage !== '{}' &&
          r.userMessage.trim()
        ) {
          historyMsgs.push({
            role: 'user',
            content: r.userMessage,
            runId: r.id,
            ts: toTs(r.createdAt),
          });
        }
        // Clarifications are no longer written into messages: the clarification card is rendered by the
        // timeline, and messages keeps only user bubbles.
      }
      if (page === 1) {
        setMessages(historyMsgs);
      } else {
        setMessages((prev) => [...historyMsgs, ...prev]);
      }
    } catch {}
  }, []);

  // Update the timeline clarification row in place by runId (optionally clarificationId)
  // Apply a clarification status change to the matching row.
  // Match granularity: when clarificationId exists it [must] hit the same round; when missing,
  // only rows still PENDING within the run may match — otherwise later clarifications produced
  // by a run resume would be rewritten along with it.
  const applyClarificationState = (
    rows: any[],
    d: any,
    state: string,
    response?: string,
  ) =>
    rows.map((r) => {
      if (r.kind !== 'clarification') return r;
      if (Number(r.runId) !== Number(d?.runId)) return r;
      const wantId = d?.clarificationId;
      const haveId = r.content?.clarificationId;
      if (wantId != null && haveId != null) {
        if (String(haveId) !== String(wantId)) return r;
      } else if (r.state !== TIMELINE_STATE.PENDING) {
        // When there is no id to compare, only change rows awaiting a reply; never overwrite finalized rows
        return r;
      }
      return {
        ...r,
        state,
        content: { ...r.content, ...(response != null ? { response } : {}) },
      };
    });

  // Immediately release all local PENDING clarifications (instant unlock on stop/cancel, without waiting for SSE)
  const cancelPendingClarificationsLocally = () =>
    setTimeline((prev) =>
      prev.map((r) =>
        r.kind === 'clarification' && r.state === TIMELINE_STATE.PENDING
          ? { ...r, state: 'CANCELLED' }
          : r,
      ),
    );

  const mergeTimeline = (rows: any[]) => {
    if (!Array.isArray(rows)) return;
    setTimeline((prev) => {
      const m = new Map<number, any>(prev.map((r) => [r.seq, r]));
      for (const r of rows) {
        if (r && r.seq != null) {
          // When the authoritative sub-agent row arrives, remove the SSE placeholder row (seq<0) so the same subId does not appear twice
          if (r.kind === 'subagent' && r.refSubAgentRunId != null) {
            const pid = Number(r.refSubAgentRunId);
            for (const [seq, row] of m) {
              if (
                row &&
                row.kind === 'subagent' &&
                Number(row.seq) < 0 &&
                Number(row.refSubAgentRunId) === pid
              ) {
                m.delete(seq);
              }
            }
          }
          const incoming = { ...(m.get(r.seq) || {}), ...r };
          // When the authoritative assistant row for a running run is empty, do not overwrite
          // locally streamed body/reasoning with empty content,
          // otherwise a retry/tail-window refresh would make the reply the user already saw
          // suddenly disappear.
          const existingRow = m.get(r.seq);
          if (
            existingRow &&
            incoming.kind === 'assistant' &&
            (!incoming.state || incoming.state === TIMELINE_STATE.RUNNING) &&
            !(incoming.content?.text ?? '').trim() &&
            !(incoming.content?.thinking ?? '').trim() &&
            ((existingRow.content?.text ?? '').trim() ||
              (existingRow.content?.thinking ?? '').trim())
          ) {
            incoming.content = {
              ...incoming.content,
              text: incoming.content?.text || existingRow.content?.text,
              thinking:
                incoming.content?.thinking || existingRow.content?.thinking,
            };
          }
          // Locally answered clarifications are not reverted by a stale server PENDING. Matched
          // by clarificationId; when the id is missing no guard is applied (see the
          // clarificationKey comment).
          if (
            incoming.kind === 'clarification' &&
            incoming.state === TIMELINE_STATE.PENDING
          ) {
            const key = clarificationKey(
              incoming.runId,
              incoming.content?.clarificationId,
            );
            if (key && answeredClarificationsRef.current.has(key)) {
              incoming.state = 'ANSWERED';
            }
          }
          m.set(r.seq, incoming);
        }
      }
      return [...m.values()].sort((a, b) => a.seq - b.seq);
    });
  };

  // Streaming token merge: at most one setTimeline per frame, and only the currently streaming
  // row changes reference,
  // which together with RowCard memo avoids re-rendering the whole page/list.
  const flushStreamingDeltas = useCallback(() => {
    deltaRafRef.current = null;
    const batch = pendingDeltaRef.current;
    if (batch.size === 0) return;
    pendingDeltaRef.current = new Map();

    setTimeline((prev) => {
      const next = [...prev];
      const indexBySeq = new Map<number, number>();
      for (let i = 0; i < next.length; i += 1) {
        indexBySeq.set(Number(next[i].seq), i);
      }

      for (const [seq, delta] of batch) {
        const idx = indexBySeq.get(seq);
        if (idx == null) {
          next.push({
            seq,
            kind: 'assistant',
            status: TIMELINE_STATE.RUNNING,
            content: { ...delta },
          });
          indexBySeq.set(seq, next.length - 1);
          continue;
        }
        const row = next[idx];
        next[idx] = {
          ...row,
          content: {
            ...(row.content ?? {}),
            ...(delta.reply
              ? { reply: (row.content?.reply ?? '') + delta.reply }
              : {}),
            ...(delta.thinking
              ? { thinking: (row.content?.thinking ?? '') + delta.thinking }
              : {}),
          },
        };
      }
      return next.sort((a, b) => Number(a.seq) - Number(b.seq));
    });
  }, []);

  const enqueueStreamingDelta = useCallback(
    (seq: number, field: 'reply' | 'thinking', delta: string) => {
      if (!delta) return;
      const current = pendingDeltaRef.current.get(seq) ?? {};
      current[field] = (current[field] ?? '') + delta;
      pendingDeltaRef.current.set(seq, current);
      if (deltaRafRef.current == null) {
        deltaRafRef.current = requestAnimationFrame(flushStreamingDeltas);
      }
    },
    [flushStreamingDeltas],
  );

  useEffect(() => {
    requestEpochRef.current += 1;
    pendingDeltaRef.current.clear();
    if (deltaRafRef.current != null) {
      cancelAnimationFrame(deltaRafRef.current);
      deltaRafRef.current = null;
    }
  }, [sessionId]);

  const bumpCursors = (rows: any[]) => {
    if (!Array.isArray(rows) || rows.length === 0) return;
    const seqs = rows.map((r) => Number(r.seq));
    const min = Math.min(...seqs);
    const max = Math.max(...seqs);
    oldestSeqRef.current =
      oldestSeqRef.current == null ? min : Math.min(oldestSeqRef.current, min);
    newestSeqRef.current =
      newestSeqRef.current == null ? max : Math.max(newestSeqRef.current, max);
  };

  const loadInitialTimeline = async (sid: number, limit = 10) => {
    try {
      const page = await agentApi.sessions.getTimeline(sid, { limit });
      const rows = Array.isArray(page?.rows) ? page.rows : [];
      mergeTimeline(rows);
      bumpCursors(rows);
      setTimelineHasMore(Boolean(page?.hasMore));
    } catch {
      // Not enabled on the backend / request failed: ignore
    }
  };

  const loadOlderTimeline = async () => {
    if (!sessionId || oldestSeqRef.current == null) return;
    try {
      const page = await agentApi.sessions.getTimeline(Number(sessionId), {
        beforeSeq: oldestSeqRef.current,
        limit: 5,
      });
      const rows = Array.isArray(page?.rows) ? page.rows : [];
      mergeTimeline(rows);
      bumpCursors(rows);
      setTimelineHasMore(Boolean(page?.hasMore));
    } catch {
      // ignore
    }
  };

  const refreshLatest = async (sid: number) => {
    const epoch = requestEpochRef.current;
    // afterSeq pagination on the backend is `LIMIT size+1` (AgentTimelineServiceImpl#list). If more
    // than limit rows are added between two refreshes, a single call only fills up to the limit-th
    // row, and the remaining rows are flagged by hasMore without any path to "keep pulling forward"
    // (loadOlderTimeline uses beforeSeq, the opposite direction) → the middle rows stay missing until
    // the next refresh. This loop keeps pulling until empty or no longer hasMore; a round cap prevents
    // an infinite loop in abnormal cases.
    const LIMIT = 10;
    const MAX_ROUNDS = 20;
    let round = 0;
    let more = false;
    do {
      try {
        // Empty cursor (first message of a new session) → fetch the tail page directly; otherwise append incrementally by afterSeq
        const page = await agentApi.sessions.getTimeline(
          sid,
          newestSeqRef.current != null
            ? { afterSeq: newestSeqRef.current, limit: LIMIT }
            : { limit: LIMIT },
        );
        if (epoch !== requestEpochRef.current) return;
        const rows = Array.isArray(page?.rows) ? page.rows : [];
        const before = newestSeqRef.current;
        if (rows.length) {
          mergeTimeline(rows);
          bumpCursors(rows);
        }
        more = !!page?.hasMore;
        if (more) setTimelineHasMore(true);
        // Cursor did not advance (empty page, or the returned seq is not greater than the current
        // cursor) → nothing more to fetch, stop immediately,
        // otherwise the same page would be pulled repeatedly until MAX_ROUNDS.
        if (!rows.length || newestSeqRef.current === before) break;
      } catch {
        break;
      }
    } while (more && ++round < MAX_ROUNDS);
  };

  // Calibrate the send lock from the latest run status row. If a run terminal event is lost during an
  // SSE disconnect window, sending stays stuck at
  // true (previously recoverable only by refreshing the page); fetching the authoritative state once
  // on reconnect unlocks it.
  // Parked-state rule: RUNNING/PENDING/WAITING stay locked; terminal states and AWAITING_USER unlock
  // (AWAITING_USER is handled by the clarification card, so the input box should be usable to answer).
  // Single source for the send lock: /runtime/{sid}/state (aggregated from source tables).
  // The timeline only renders and does not take part in decisions.
  const refreshRuntimeState = async (sid: number) => {
    if (typeof document !== 'undefined' && document.hidden) return;
    if (isReadOnlySessionId(sid)) return;
    const epoch = requestEpochRef.current;
    try {
      const state = await agentApi.sessions.getRuntimeState(sid);
      if (
        currentSessionIdRef.current !== sid ||
        epoch !== requestEpochRef.current
      )
        return;
      setRuntimeState(state);
      setSending(
        Array.isArray(state?.blockReasons) &&
          state.blockReasons.includes('RUNNING'),
      );
    } catch {
      // No fallback: errors go to the global request prompt; the send lock keeps the last authoritative value
    }
  };

  // Reasoning effort capability: the dynamic default is computed by the backend from the actual output
  // limit; infeasible levels are still returned, and the frontend only warns.
  const refreshReasoningCapability = async (sid: number, routeId?: number) => {
    if (isReadOnlySessionId(sid)) return;
    const epoch = requestEpochRef.current;
    try {
      const cap = await agentApi.sessions.getReasoningCapability(sid, routeId);
      if (
        currentSessionIdRef.current !== sid ||
        epoch !== requestEpochRef.current
      )
        return;
      setReasoningCapability(cap);
      if (!cap?.supported) {
        setReasoningEffort(undefined);
        return;
      }
      // A running run has a fixed level; when idle, follow the dynamic default only if the user has not manually chosen one.
      if (cap.running) {
        setReasoningEffort(cap.selectedLevel ?? undefined);
      } else if (!reasoningTouchedRef.current) {
        setReasoningEffort(cap.defaultLevel ?? undefined);
      }
    } catch {
      if (currentSessionIdRef.current !== sid) return;
      setReasoningCapability(null);
    }
  };
  const refreshReasoningRef = useRef(refreshReasoningCapability);
  refreshReasoningRef.current = refreshReasoningCapability;

  // Full-page overwrite refresh after tool events: overwrite rows with the same seq to backfill
  // content persisted late (e.g. screenshot images).
  // The afterSeq increment skips existing rows with seq ≤ newest (tool rows), so screenshots stay
  // invisible during the live phase; pull the tail window here to overwrite and correct.
  const refreshTail = async (sid: number) => {
    const epoch = requestEpochRef.current;
    try {
      const page = await agentApi.sessions.getTimeline(sid, { limit: 30 });
      if (epoch !== requestEpochRef.current) return;
      const rows = Array.isArray(page?.rows) ? page.rows : [];
      if (rows.length) {
        mergeTimeline(rows);
        bumpCursors(rows);
      }
      if (page?.hasMore) setTimelineHasMore(true);
    } catch {
      // ignore
    }
  };

  // High-frequency request coalescing: tool events / runtime-state events hit the backend only once
  // within a 100~250ms window;
  // triggers arriving during execution are merged into "run once more after completion", avoiding a
  // concurrency storm.
  const refreshLatestRef = useRef(refreshLatest);
  refreshLatestRef.current = refreshLatest;
  const refreshStateRef = useRef(refreshRuntimeState);
  refreshStateRef.current = refreshRuntimeState;
  const refreshTailRef = useRef(refreshTail);
  refreshTailRef.current = refreshTail;

  const refreshLatestCoalesced = useRef(
    createCoalescedTask((sid: number) => refreshLatestRef.current(sid), 100),
  ).current;
  const refreshStateCoalesced = useRef(
    createCoalescedTask((sid: number) => refreshStateRef.current(sid), 250),
  ).current;
  const refreshTailCoalesced = useRef(
    createCoalescedTask((sid: number) => refreshTailRef.current(sid), 150),
  ).current;

  // Fetch the authoritative runtime state once when the tab returns to the foreground: there is no background polling, so avoid leaving the send lock at a stale value.
  useEffect(() => {
    const onVisible = () => {
      const sid = currentSessionIdRef.current;
      if (!document.hidden && sid != null) refreshStateCoalesced(sid);
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [refreshStateCoalesced]);

  // Session-level usage: fetch the SUM after a run reaches terminal state, driving the bottom usage bar of the chat area
  const refreshSessionUsage = async (sid: number) => {
    if (!sid) return;
    try {
      const u = await agentApi.sessions.usage(sid);
      setSessionUsage(u);
    } catch {
      // ignore
    }
  };

  const refreshToolStats = async (sid: number) => {
    if (!sid) return;
    try {
      const stats = await agentApi.sessions.toolStats(
        sid,
        currentRunIdRef.current,
      );
      setToolStats(stats);
    } catch {
      // ignore
    }
  };

  const refreshQueue = async (sid: number) => {
    if (!sid || isReadOnlySessionId(sid)) return;
    try {
      const rows = await agentApi.sessions.pendingQueue(sid);
      setQueuedMessages(Array.isArray(rows) ? rows : []);
    } catch {
      // ignore
    }
  };

  const withdrawQueued = async (queueId: string) => {
    if (!currentSession?.id || !queueId) return;
    try {
      const res = await agentApi.sessions.withdrawQueue(
        currentSession.id,
        queueId,
      );
      if (res?.result === 'ALREADY_CONSUMED') {
        message.warning(
          intl.formatMessage({
            id: 'pages.chat.withdrawAlreadyRunning',
            defaultMessage: 'Message already started and cannot be withdrawn',
          }),
        );
      } else if (res?.result === 'NOT_FOUND') {
        message.warning(
          intl.formatMessage({
            id: 'pages.chat.queueNotFound',
            defaultMessage: 'Queued message not found',
          }),
        );
      }
      await refreshQueue(currentSession.id);
    } catch (e: any) {
      message.error(
        e?.message ||
          intl.formatMessage({
            id: 'pages.chat.withdrawFailed',
            defaultMessage: 'Failed to withdraw',
          }),
      );
    }
  };

  useEffect(() => {
    setToolStats(null);
    if (currentSession?.id) {
      void refreshSessionUsage(currentSession.id);
      void refreshToolStats(currentSession.id);
      void refreshQueue(currentSession.id);
    }
  }, [currentSession?.id]);

  useEffect(() => {
    setRunMode(currentSession?.runMode === 'READ_ONLY' ? 'READ_ONLY' : 'WRITE');
    setToolApprovalBatch(null);
  }, [currentSession?.id, currentSession?.runMode]);

  useEffect(() => {
    if (sessionPanelOpen && currentSession?.id) {
      void refreshToolStats(currentSession.id);
    }
  }, [sessionPanelOpen, currentSession?.id]);

  useEffect(() => {
    if (!currentSession?.id || queuedMessages.length === 0) return;
    const sid = currentSession.id;
    const timer = window.setInterval(
      () => void refreshQueue(sid),
      TIMING.QUEUE_REFRESH_MS,
    );
    return () => window.clearInterval(timer);
  }, [currentSession?.id, queuedMessages.length]);

  const handleRunModeChange = async (mode: 'WRITE' | 'READ_ONLY') => {
    setRunMode(mode);
    if (!currentSession?.id) return;
    try {
      const updated = await agentApi.sessions.updateRunMode(
        currentSession.id,
        mode,
      );
      setCurrentSession((prev: any) =>
        prev?.id === currentSession.id
          ? { ...prev, runMode: updated?.runMode || mode }
          : prev,
      );
      setSessions((prev) =>
        prev.map((s) =>
          s.id === currentSession.id
            ? { ...s, runMode: updated?.runMode || mode }
            : s,
        ),
      );
    } catch {
      // keep local toggle; next session reload restores server value
    }
  };

  useEffect(() => {
    const handler = (event: Event) => {
      const sid = Number(
        (event as CustomEvent<{ sessionId?: number }>).detail?.sessionId,
      );
      if (sid && currentSessionIdRef.current === sid) {
        void refreshSessionUsage(sid);
      }
    };
    window.addEventListener(SESSION_USAGE_REFRESH_EVENT, handler);
    return () =>
      window.removeEventListener(SESSION_USAGE_REFRESH_EVENT, handler);
  }, []);

  const handleTimelineClarify = async (row: any, response: string) => {
    if (!currentSession?.id || !row?.runId) return;
    const key = clarificationKey(row.runId, row.clarificationId);
    // Record as answered first, preventing the subsequent refreshLatest from overwriting the
    // optimistic state with a stale server PENDING.
    // Do not record when the id is missing: the specific clarification round cannot be located, and
    // recording the runId would wrongly affect later clarifications of the same run.
    if (key) answeredClarificationsRef.current.add(key);
    // Optimistic echo: immediately mark the clarification row as answered and show the user's submitted content
    setTimeline((prev) =>
      applyClarificationState(
        prev,
        { runId: row.runId, clarificationId: row.clarificationId },
        'ANSWERED',
        response,
      ),
    );
    try {
      await agentApi.sessions.clarify(
        currentSession.id,
        row.runId,
        response,
        row.clarificationId,
      );
    } catch (e: any) {
      // Submission failed (duplicate submit / clarification expired): revert the local answer and fetch the authoritative state to avoid a deadlock
      if (key) answeredClarificationsRef.current.delete(key);
      message.error(
        e?.message ||
          intl.formatMessage({
            id: 'pages.chat.clarifyFailed',
            defaultMessage: 'Failed to submit clarification, please retry',
          }),
      );
      void refreshLatest(currentSession.id);
    }
  };

  // Sub-agent live steps: built incrementally from SSE events (purely driven, no polling / throttled
  // endpoint pulls).
  // - reasoning_token first frame (firstFrame) → switch to a new LLM round; otherwise → append to the
  //   current round's thinking;
  // - content_token → append to the current round's reply;
  // - tool_call_{started,in_progress,succeeded,failed} → update the tool step in place by publishId.
  /** Parse browser screenshot references from the tool result artifact JSON (data.screenshot.fileKey/contentType). */
  const parseScreenshotRef = (artifact?: string) => {
    if (!artifact) return null;
    try {
      const root = JSON.parse(artifact);
      const shot = root?.data?.screenshot;
      if (!shot?.fileKey) return null;
      return {
        fileKey: String(shot.fileKey),
        contentType: shot.contentType || 'image/jpeg',
      };
    } catch {
      return null;
    }
  };

  const handleSubAgentLiveEvent = useCallback((evtType: string, d: any) => {
    const subId = Number(d.subAgentRunId);
    if (!Number.isFinite(subId)) return;
    const delta = String(d?.response ?? '');
    if (evtType === 'content_token') {
      setSubAgentLive((prev) => {
        const arr: any[] = prev[subId] || [];
        if (!arr.length) {
          return {
            ...prev,
            [subId]: [
              { type: 'llm', reasoning: '', reply: delta, running: true },
            ],
          };
        }
        const last = arr[arr.length - 1];
        if (last.type !== 'llm' || !last.running) {
          return {
            ...prev,
            [subId]: [
              ...arr,
              { type: 'llm', reasoning: '', reply: delta, running: true },
            ],
          };
        }
        const idx = arr.length - 1;
        return {
          ...prev,
          [subId]: arr.map((it, i) =>
            i === idx ? { ...it, reply: it.reply + delta } : it,
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
        // Tool result contains browser screenshots → live steps carry images directly (after refresh, the backend timeline is consistent)
        const shot = parseScreenshotRef(update.artifact);
        if (shot) update.images = [shot];
        setSubAgentLive((prev) => {
          const arr: any[] = prev[subId] || [];
          const idx = arr.findIndex(
            (it) =>
              it.type === 'tool_call' && it.publishId === update.publishId,
          );
          if (idx >= 0) {
            return {
              ...prev,
              [subId]: arr.map((it, i) =>
                i === idx ? { ...it, ...update, status } : it,
              ),
            };
          }
          return {
            ...prev,
            [subId]: [...arr, { ...update, status }],
          };
        });
        return;
      }
      if (subType === 'model_reason') {
        if (d?.firstFrame) {
          // New LLM round: strip the first-frame sentinel line ("<marker>id: name\n"), marking the
          // end of the old round
          // Both old and new prefixes are accepted (`▶ Agent ` / `▶ Skill `) so historical sessions do
          // not break
          const body = stripSubAgentSentinel(delta);
          setSubAgentLive((prev) => {
            const arr: any[] = prev[subId] || [];
            const closed = arr.map((it) =>
              it.type === 'llm' && it.running ? { ...it, running: false } : it,
            );
            return {
              ...prev,
              [subId]: [
                ...closed,
                { type: 'llm', reasoning: body, reply: '', running: true },
              ],
            };
          });
        } else {
          setSubAgentLive((prev) => {
            const arr: any[] = prev[subId] || [];
            if (!arr.length) {
              return {
                ...prev,
                [subId]: [
                  { type: 'llm', reasoning: delta, reply: '', running: true },
                ],
              };
            }
            const last = arr[arr.length - 1];
            if (last.type !== 'llm' || !last.running) {
              return {
                ...prev,
                [subId]: [
                  ...arr,
                  { type: 'llm', reasoning: delta, reply: '', running: true },
                ],
              };
            }
            const idx = arr.length - 1;
            return {
              ...prev,
              [subId]: arr.map((it, i) =>
                i === idx ? { ...it, reasoning: it.reasoning + delta } : it,
              ),
            };
          });
        }
      }
    }
  }, []);

  const parseToolApproval = useCallback(
    (payload: any): ToolApprovalBatch | null => {
      if (!payload?.approvalId || !payload?.toolCallsJson) return null;
      try {
        const calls = JSON.parse(payload.toolCallsJson);
        if (!Array.isArray(calls) || calls.length === 0) return null;
        return {
          approvalId: String(payload.approvalId),
          runId: Number(payload.runId),
          calls,
        };
      } catch {
        return null;
      }
    },
    [],
  );

  const refreshPendingToolApproval = useCallback(async (sid: number) => {
    if (isReadOnlySessionId(sid)) return;
    try {
      const vo = await agentApi.toolApprovals.pending(sid);
      if (!vo?.approvalId || !vo?.toolCallsJson) {
        setToolApprovalBatch(null);
        return;
      }
      const calls = JSON.parse(vo.toolCallsJson);
      const pendingCalls = Array.isArray(calls)
        ? calls.filter((c: any) => c?.pending !== false)
        : [];
      if (pendingCalls.length === 0) {
        setToolApprovalBatch(null);
        return;
      }
      setToolApprovalBatch({
        approvalId: String(vo.approvalId),
        runId: Number(vo.runId),
        calls: pendingCalls,
      });
    } catch {
      // keep current drawer on transient errors
    }
  }, []);

  const resolveToolApproval = async (
    decisions: Array<{ callId: string; decision: string }>,
  ) => {
    if (!toolApprovalBatch || !currentSession?.id) return;
    setToolApprovalSubmitting(true);
    try {
      await agentApi.toolApprovals.resolve(
        currentSession.id,
        toolApprovalBatch.runId,
        toolApprovalBatch.approvalId,
        decisions,
      );
      setToolApprovalBatch(null);
      await refreshPendingToolApproval(currentSession.id);
      await refreshToolStats(currentSession.id);
    } catch (e: any) {
      message.error(e?.message || 'Tool approval failed');
    } finally {
      setToolApprovalSubmitting(false);
    }
  };

  useEffect(() => {
    if (currentSession?.id) {
      void refreshPendingToolApproval(currentSession.id);
    }
  }, [currentSession?.id, refreshPendingToolApproval]);

  const refreshPendingClarifications = useCallback(async (sid: number) => {
    if (isReadOnlySessionId(sid)) return;
    try {
      const rows = await agentApi.sessions.pendingClarifications(sid);
      const pending = (rows || []).filter(
        (row: any) => row?.clarificationId && row?.type !== 'tool_approval',
      );
      if (pending.length === 0) {
        setClarificationBatch(null);
        return;
      }
      setClarificationBatch({
        runId: Number(pending[0].runId),
        items: pending.map((row: any) => ({
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
  }, []);

  const resolveClarifications = async (
    answers: Array<{
      clarificationId: string;
      selected?: string[];
      text?: string;
    }>,
  ) => {
    if (!clarificationBatch || !currentSession?.id) return;
    setClarificationSubmitting(true);
    try {
      await agentApi.sessions.resolveClarifications(
        currentSession.id,
        clarificationBatch.runId,
        answers,
      );
      setClarificationBatch(null);
      await refreshPendingClarifications(currentSession.id);
      void refreshLatest(currentSession.id);
    } catch (e: any) {
      message.error(
        e?.message ||
          intl.formatMessage({
            id: 'pages.chat.clarifyFailed',
            defaultMessage: 'Failed to submit clarification, please retry',
          }),
      );
      await refreshPendingClarifications(currentSession.id);
    } finally {
      setClarificationSubmitting(false);
    }
  };

  useEffect(() => {
    if (currentSession?.id) {
      void refreshPendingClarifications(currentSession.id);
    }
  }, [currentSession?.id, refreshPendingClarifications]);

  // SSE events may be lost or emitted before the connection is established: do short bounded polling
  // while a run is active
  // to ensure the approval/clarification overlays appear even without relying on a single path.
  useEffect(() => {
    if (!currentSession?.id || !sending) return;
    const sid = currentSession.id;
    const timer = window.setInterval(() => {
      void refreshPendingToolApproval(sid);
      void refreshPendingClarifications(sid);
    }, TIMING.APPROVAL_POLL_MS);
    const stop = window.setTimeout(
      () => window.clearInterval(timer),
      TIMING.APPROVAL_POLL_TIMEOUT_MS,
    );
    return () => {
      window.clearInterval(timer);
      window.clearTimeout(stop);
    };
  }, [
    currentSession?.id,
    sending,
    refreshPendingToolApproval,
    refreshPendingClarifications,
  ]);

  const connectSSE = useCallback((sid: number) => {
    if (sseStoppedRef.current) return;
    if (reconnectTimerRef.current != null) {
      window.clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (abortRef.current) abortRef.current.abort();
    // Clear locally answered markers when switching sessions (memory only, so a different session does not wrongly suppress refreshes)
    if (currentSessionIdRef.current !== sid) {
      answeredClarificationsRef.current.clear();
    }
    currentSessionIdRef.current = sid;
    setSseConnected(false);
    props.onSseStatusChange?.(false);
    reconnectCountRef.current = 0;

    const controller = new AbortController();
    abortRef.current = controller;
    const token = getToken();
    if (!token) {
      // Do not silently give up when the token is briefly empty: retry after 1s so SSE does not stay disconnected forever
      reconnectTimerRef.current = window.setTimeout(() => {
        reconnectTimerRef.current = null;
        if (!sseStoppedRef.current && currentSessionIdRef.current === sid) {
          connectSSE(sid);
        }
      }, 1000);
      return;
    }

    connectSse(
      API_PATHS.RUNTIME_STREAM(sid),
      token,
      {
        onOpen: () => {
          if (sseStoppedRef.current) return;
          const wasReconnect = reconnectCountRef.current > 0;
          setSseConnected(true);
          props.onSseStatusChange?.(true);
          reconnectCountRef.current = 0;
          console.log('[SSE] connected');
          if (wasReconnect) {
            // Reconnect: backfill rows lost during the disconnect window and calibrate the send lock
            // from the authoritative run state,
            // otherwise a lost terminal event leaves send/stop locked forever (recoverable only by
            // refreshing the page).
            refreshLatestCoalesced(sid);
            refreshStateCoalesced(sid);
          }
          void refreshPendingToolApproval(sid);
          void refreshPendingClarifications(sid);
          refreshStateCoalesced(sid);
        },
        onMessage: (payload) => {
          if (sseStoppedRef.current) return;
          try {
            const parsed = JSON.parse(payload);
            const evtType = parsed.eventType || parsed.type || '';
            const d = parsed.data || parsed;

            // Session guard: ignore events from other sessions, preventing a still-streaming old session from polluting the current messages/timeline (cross-talk/duplicates)
            if (currentSessionIdRef.current !== sid) return;

            if (evtType === 'TOOL_APPROVAL_PENDING') {
              const batch = parseToolApproval(d);
              if (batch) setToolApprovalBatch(batch);
              refreshStateCoalesced(sid);
              refreshLatestCoalesced(sid);
              return;
            }
            if (
              evtType === 'TOOL_APPROVAL_RESPONDED' ||
              evtType === 'TOOL_APPROVAL_DISMISSED'
            ) {
              setToolApprovalBatch((prev) =>
                prev && prev.approvalId === String(d?.approvalId) ? null : prev,
              );
              void refreshPendingToolApproval(sid);
              refreshLatestCoalesced(sid);
              return;
            }

            // Sub-agent live: purely SSE-driven incremental building (content/reasoning/tool events all carry subAgentRunId)
            if (d?.subAgentRunId != null) {
              handleSubAgentLiveEvent(evtType, d);
              // Header-row fallback: during a pure-LLM sub-run there is no run terminal / tool end
              // event to trigger refreshLatest,
              // and if the sub-agent header row has not entered the timeline, SubAgentCard will not
              // mount → live cannot render.
              // Insert a RUNNING placeholder row here in place (later refreshLatest/terminal merges
              // replace it with the authoritative row).
              const liveSubId = Number(d.subAgentRunId);
              if (Number.isFinite(liveSubId)) {
                setTimeline((prev) => {
                  const exists = prev.some(
                    (r) =>
                      r.kind === 'subagent' &&
                      Number(r.refSubAgentRunId) === liveSubId,
                  );
                  if (exists) return prev;
                  return [
                    ...prev,
                    {
                      // Placeholder seq is uniquely negative per subId (aligned with the widget's
                      // placeholderSeq):
                      // parallel sub-agents will have several placeholder rows at once, and a
                      // hardcoded -1 would cause duplicate React keys
                      // `-1-subagent`, and mergeTimeline building a Map by seq would overwrite each
                      // other and lose data
                      seq: -(Math.abs(liveSubId) + 1),
                      kind: 'subagent',
                      refSubAgentRunId: liveSubId,
                      status: TIMELINE_STATE.RUNNING,
                      title:
                        d?.displayNameCn ||
                        d?.displayName ||
                        intl.formatMessage({
                          id: 'pages.chat.tool.subagent',
                          defaultMessage: 'Sub-agent',
                        }),
                      content: {
                        displayName: d?.displayNameCn || d?.displayName,
                        state: 'RUNNING',
                        // Parallel sub-agents in the same batch share parentToolCallId, used for Tab grouping
                        parentToolCallId: d?.parentToolCallId ?? null,
                      },
                    },
                  ].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
                });
              }
            }

            // Unified Timeline streaming / row backfill (backend envelopes already carry seq/kind):
            // - assistant container tokens (content/reasoning) → append in place by seq with the typewriter;
            // - run terminal / tool end / clarification → pull the latest page by afterSeq to backfill
            //   rows (tool/clarification rows are not seq-tagged yet).
            const tlSubType = String(
              d?.reasoningSubType || d?.status || evtType,
            );

            if (tlSubType === 'tool_input_preparing') {
              setPreparingTool({
                publishId: d?.publishId ? String(d.publishId) : undefined,
                name:
                  d?.displayNameCn ||
                  d?.displayNameEn ||
                  d?.toolName ||
                  intl.formatMessage({
                    id: 'pages.chat.tool.tool',
                    defaultMessage: 'Tool',
                  }),
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

            // Run runtime/terminal state: top-level fallback for unlocking/setting, not relying on the
            // reasoning_token envelope,
            // so sending does not stay stuck forever when a terminal event arrives in another envelope.
            if (
              tlSubType === 'run_completed' ||
              tlSubType === 'run_failed' ||
              tlSubType === 'run_cancelled' ||
              tlSubType === 'run_awaiting_user'
            ) {
              setSending(false);
              refreshStateCoalesced(sid);
              if (tlSubType === 'run_awaiting_user') {
                void refreshPendingToolApproval(sid);
                void refreshPendingClarifications(sid);
              }
              if (stopFallbackRef.current) {
                clearTimeout(stopFallbackRef.current);
                stopFallbackRef.current = null;
              }
            } else if (
              tlSubType === 'run_running' ||
              tlSubType === 'run_pending' ||
              // wait suspended: the run has not ended and will resume on its own when due, so the input
              // box should stay locked.
              // If a reconnect happens to land inside the waiting window, this re-establishes sending.
              tlSubType === 'run_waiting'
            ) {
              setSending(true);
              refreshStateCoalesced(sid);
            }

            // run_status is "one row per run, updated in place": the event already carries
            // seq/status/title,
            // so merge in place directly; the afterSeq increment cannot get updates for the same seq
            // (retry counts are not refreshed).
            if (d?.seq != null && d?.kind === 'run_status') {
              const rsSeq = Number(d.seq);
              const rsState =
                d?.status ||
                (tlSubType === 'run_waiting' ? 'WAITING' : undefined);
              const rsText =
                d?.statusTitle || d?.assistantReply || d?.response || '';
              mergeTimeline([
                {
                  seq: rsSeq,
                  runId: d?.runId != null ? Number(d.runId) : undefined,
                  kind: 'run_status',
                  state: rsState,
                  subtype: d?.waitKind === 'LLM_RETRY' ? 'LLM_RETRY' : rsState,
                  title: rsText,
                  content: { text: rsText, state: rsState },
                },
              ]);
            }

            // Context compaction: insert a row locally on the RUNNING event (the backend has already
            // backfilled seq/kind), and append output tokens to the body.
            // Deliberately do not refreshLatest while compaction_running — a shallow merge would
            // overwrite already-streamed text with an authoritative row that "has no body yet".
            if (d?.seq != null && d?.kind === 'compaction') {
              const seq = Number(d.seq);
              const delta =
                evtType === 'content_token' ? String(d?.response || '') : '';
              const terminal =
                tlSubType === 'compaction_completed' ||
                tlSubType === 'compaction_failed';
              setTimeline((prev) => {
                const m = new Map<number, any>(prev.map((r) => [r.seq, r]));
                const existing = m.get(seq);
                const content = { ...(existing?.content || {}) };
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
                    intl.formatMessage({
                      id: 'pages.chat.compacting',
                      defaultMessage: 'Compacting context…',
                    }),
                  content,
                });
                return [...m.values()].sort((a, b) => a.seq - b.seq);
              });
            }

            if (
              d?.seq != null &&
              d?.kind === 'assistant' &&
              d?.response &&
              (evtType === 'content_token' || evtType === 'reasoning_token')
            ) {
              // Merge into the next frame and flush together: 50 tokens/s no longer triggers 50 full array rebuilds.
              enqueueStreamingDelta(
                Number(d.seq),
                evtType === 'content_token' ? 'reply' : 'thinking',
                String(d.response),
              );
            } else if (
              [
                'run_completed',
                'run_failed',
                'run_cancelled',
                'run_awaiting_user',
                'run_waiting',
                'tool_call_succeeded',
                'tool_call_failed',
                'compaction_completed',
                'compaction_failed',
              ].includes(tlSubType) ||
              String(evtType).startsWith('clarification_')
            ) {
              // Tool event: full-page overwrite refresh (backfills screenshots persisted late, etc.),
              // avoiding the afterSeq increment skipping existing tool rows.
              // Same for run_status: the row is updated in place, so afterSeq cannot pull the new
              // state/retry count for the same seq.
              const isRunStatusSub = [
                'run_completed',
                'run_failed',
                'run_cancelled',
                'run_awaiting_user',
                'run_waiting',
              ].includes(tlSubType);
              if (
                tlSubType === 'tool_call_succeeded' ||
                tlSubType === 'tool_call_failed' ||
                isRunStatusSub
              ) {
                refreshTailCoalesced(sid);
              } else {
                refreshLatestCoalesced(sid);
              }
            }
            // Run terminal state: refresh session-level usage (bottom SUM bar of the chat area)
            if (
              tlSubType === 'run_completed' ||
              tlSubType === 'run_failed' ||
              tlSubType === 'run_cancelled'
            ) {
              void refreshSessionUsage(sid);
            }

            // Always track the current runId (including runs started by the task system) so stop hits the right run
            const msgRunId = Number(d?.runId);
            if (Number.isFinite(msgRunId)) {
              currentRunIdRef.current = msgRunId;
            }

            if (evtType === 'reasoning_token') {
              const subType = d?.reasoningSubType || '';
              const publishId = d?.publishId;
              const runId = d?.runId;

              if (
                subType === 'tool_call_in_progress' ||
                subType === 'tool_call_succeeded' ||
                subType === 'tool_call_failed'
              ) {
                const name =
                  d?.displayNameCn || d?.displayName || d?.toolName || '';
                const nameEn = d?.displayNameEn || name;
                const status =
                  subType === 'tool_call_in_progress'
                    ? 'in_progress'
                    : subType === 'tool_call_succeeded'
                      ? 'succeeded'
                      : 'failed';
                if (currentSession?.id)
                  void refreshToolStats(currentSession.id);
                setToolCalls((prev) =>
                  prev.map((tc) =>
                    tc._publishId === publishId
                      ? {
                          ...tc,
                          name: name || tc.name,
                          displayNameCn: name,
                          displayNameEn: nameEn,
                          status,
                        }
                      : tc,
                  ),
                );
                setMessages((prev) => {
                  const idx = publishId
                    ? prev.findIndex((m) => (m as any)._publishId === publishId)
                    : -1;
                  if (idx >= 0) {
                    let updated = prev[idx].content;
                    if (subType === 'tool_call_in_progress') {
                      updated = updated.replace('starting...', 'calling...');
                    } else if (subType === 'tool_call_succeeded') {
                      updated = updated.replace('calling...', 'succeeded ✅');
                    } else if (subType === 'tool_call_failed') {
                      updated = updated.replace('calling...', 'failed ❌');
                    }
                    return prev.map((m, i) =>
                      i === idx ? { ...m, content: updated } : m,
                    );
                  }
                  // No match by publishId — fallback to last reasoning line
                  const lastIdx = prev.findLastIndex(
                    (m: any) => m.role === 'reasoning',
                  );
                  if (lastIdx >= 0 && (prev[lastIdx] as any)._runId === runId) {
                    const last = prev[lastIdx];
                    const lines = last.content.split('\n');
                    const lastLine = lines[lines.length - 1];
                    if (lastLine.includes(`**${name}**`)) {
                      if (
                        subType === 'tool_call_in_progress' &&
                        lastLine.includes('starting...')
                      )
                        lines[lines.length - 1] = lastLine.replace(
                          'starting...',
                          'calling...',
                        );
                      else if (
                        subType === 'tool_call_succeeded' &&
                        lastLine.includes('calling...')
                      )
                        lines[lines.length - 1] = lastLine.replace(
                          'calling...',
                          'succeeded ✅',
                        );
                      else if (
                        subType === 'tool_call_failed' &&
                        lastLine.includes('calling...')
                      )
                        lines[lines.length - 1] = lastLine.replace(
                          'calling...',
                          'failed ❌',
                        );
                      return prev.map((m, i) =>
                        i === lastIdx ? { ...m, content: lines.join('\n') } : m,
                      );
                    }
                  }
                  return prev;
                });
                if (subType === 'tool_call_succeeded') {
                  const toolName = d?.toolName || '';
                  if (toolName === TODOWRITE_TOOL_NAME) {
                    agentApi.sessions
                      .getTodos(sid)
                      .then((data: any) => {
                        if (Array.isArray(data)) setTodos(data);
                      })
                      .catch(() => {});
                  }
                  if (toolName === DOCWRITE_TOOL_NAME) {
                    try {
                      const docInfo = JSON.parse(d?.artifact || '{}') || {};
                      if (docInfo.documentId) {
                        setMessages((prev) => {
                          const lastReasoning = prev.findLastIndex(
                            (m: any) =>
                              m.role === 'reasoning' &&
                              (m as any)._runId === d?.runId,
                          );
                          if (lastReasoning >= 0) {
                            return prev.map((m, i) =>
                              i === lastReasoning
                                ? {
                                    ...m,
                                    content:
                                      m.content +
                                      `\n📄 **${docInfo.title || 'Document'}**: ${(docInfo.preview || '').slice(0, 50)} [${intl.formatMessage({ id: 'pages.chat.viewDocument', defaultMessage: 'View' })}](/artifacts/documents/${docInfo.documentId})`,
                                  }
                                : m,
                            );
                          }
                          return prev;
                        });
                      }
                    } catch {}
                  }
                }
                return;
              }

              if (subType === 'tool_call_started') {
                const name =
                  d?.displayNameCn || d?.displayName || d?.toolName || '';
                const nameEn = d?.displayNameEn || name;
                if (currentSession?.id)
                  void refreshToolStats(currentSession.id);
                setToolCalls((prev) => [
                  ...prev,
                  {
                    name,
                    displayNameCn: name,
                    displayNameEn: nameEn,
                    status: 'started',
                    _publishId: publishId,
                    _runId: runId,
                    ts: Date.now(),
                  },
                ]);
                setSending(true);
                setSessionPanelOpen(true);
                return;
              }

              if (
                subType === 'run_completed' ||
                subType === 'run_failed' ||
                subType === 'run_cancelled' ||
                subType === 'run_awaiting_user'
              ) {
                setSending(false);
                refreshStateCoalesced(sid);
                void refreshReasoningRef.current(sid);
                if (currentSession?.id)
                  void refreshToolStats(currentSession.id);
                if (subType === 'run_awaiting_user') {
                  void refreshPendingToolApproval(sid);
                  void refreshPendingClarifications(sid);
                }
                if (stopFallbackRef.current) {
                  clearTimeout(stopFallbackRef.current);
                  stopFallbackRef.current = null;
                }
                const runId = d?.runId;
                // Show LLM error messages (429/502/other)
                if (subType === 'run_failed') {
                  const errorMsg = d?.errorMessage;
                  if (runId && errorMsg) {
                    setMessages((prev) => {
                      const existingIdx = prev.findIndex(
                        (m) => (m as any).runId === runId && m.role === 'ai',
                      );
                      if (existingIdx >= 0) {
                        return prev.map((m, i) =>
                          i === existingIdx
                            ? { ...m, content: errorMsg, _pending: false }
                            : m,
                        );
                      }
                      return [
                        ...prev,
                        {
                          role: 'ai',
                          content: errorMsg,
                          runId,
                          ts: Date.now(),
                          fromSSE: true,
                        },
                      ];
                    });
                  }
                }
                // Note: assistantReply is no longer aggregated into a separate AI reply bubble;
                // the main agent reply only shows inside each interaction timeline entry (grouped by
                // content_token).
                return;
              }

              if (subType === 'session_updated') {
                const sid = d?.sessionId;
                const title = d?.assistantReply;
                if (sid && title) {
                  setCurrentSession((prev: any) =>
                    prev?.id === sid ? { ...prev, title } : prev,
                  );
                  setSessions((prev) =>
                    prev.map((s: any) => (s.id === sid ? { ...s, title } : s)),
                  );
                }
                return;
              }

              // Reasoning is now rendered by the backend Timeline/TimelineList; the interaction
              // timeline assembly is no longer maintained;
              // remaining isSystem status rows (run_pending, etc.) are ignored; empty ai messages are
              // cleaned up on intent_retry.
              if (subType === 'intent_retry') {
                setMessages((prev) =>
                  prev.filter(
                    (m) => !(m.role === 'ai' && !(m as any).content?.trim()),
                  ),
                );
              }
              return;
            }
            if (evtType.startsWith('clarification_')) {
              refreshStateCoalesced(sid);
              if (evtType === 'clarification_responded') {
                // Record as answered: prevents a subsequent refreshLatest from overwriting the optimistic state with a stale server PENDING
                const key = clarificationKey(d?.runId, d?.clarificationId);
                if (key) answeredClarificationsRef.current.add(key);
                setTimeline((prev) =>
                  applyClarificationState(prev, d, 'ANSWERED', d?.response),
                );
              } else if (evtType === 'clarification_dismissed') {
                // The backend ClarificationStatus only has PENDING/RESPONDED/DISMISSED,
                // there is no clarification_expired — this branch never matched before.
                setTimeline((prev) =>
                  applyClarificationState(prev, d, 'CANCELLED'),
                );
              }
              // Clarifications all go through the bottom sheet; the timeline keeps only audit rows.
              void refreshPendingClarifications(sid);
              return;
            }
            if (evtType === 'content_token') {
              // Main agent reply text: handled by the unified Timeline typewriter in the streaming
              // branch (seq+assistant),
              // and no separate main timeline/reply bubble is maintained here anymore.
              return;
            }
          } catch {}
        },
        onError: (err: unknown) => {
          if (sseStoppedRef.current) return;
          setSseConnected(false);
          props.onSseStatusChange?.(false);
          // Diagnostics: print the disconnect reason (stream ended / connect timeout / HTTP status / underlying read error)
          const reason = err instanceof Error ? err.message : String(err || '');
          if (currentSessionIdRef.current === sid) {
            console.warn('[SSE] error:', reason);
            const delay = Math.min(
              1000 * 2 ** reconnectCountRef.current,
              30000,
            );
            reconnectCountRef.current++;
            console.log(
              '[SSE] reconnecting session',
              sid,
              `attempt ${reconnectCountRef.current} in ${delay}ms`,
            );
            reconnectTimerRef.current = window.setTimeout(() => {
              reconnectTimerRef.current = null;
              if (
                !sseStoppedRef.current &&
                currentSessionIdRef.current === sid
              ) {
                connectSSE(sid);
              }
            }, delay);
          }
        },
      },
      controller.signal,
    );
  }, []);

  useEffect(() => {
    if (sessionId) {
      const sid = Number(sessionId);
      // Open the live stream before the history/todo requests so a busy browser
      // connection pool cannot delay the SSE handshake.
      sseStoppedRef.current = false;
      connectSSE(sid);
      const pendingMsg = sessionStorage.getItem('landingPendingMsg');
      const modelRoute = sessionStorage.getItem('landingModelRouteId');

      if (pendingMsg && !pendingMsgConsumedRef.current) {
        pendingMsgConsumedRef.current = true;
        sessionStorage.removeItem('landingPendingMsg');
        sessionStorage.removeItem('landingModelRouteId');
        const s = sessions.find((x: any) => x.id === sid);
        if (s) setCurrentSession(s);
        else {
          setCurrentSession({ id: sid, title: '...' } as any);
          agentApi.sessions
            .get(sid)
            .then(setCurrentSession)
            .catch(() => {});
        }
        setMessages([{ role: 'user', content: pendingMsg, ts: Date.now() }]);
        setSending(true);
        setSessionPanelOpen(true);
        if (modelRoute) setSelectedModelRouteId(Number(modelRoute));
        const landingRunId = sessionStorage.getItem('landingRunId');
        if (landingRunId) {
          sessionStorage.removeItem('landingRunId');
          currentRunIdRef.current = Number(landingRunId);
          runUserMessageRef.current.set(Number(landingRunId), pendingMsg);
        }
        connectSSE(sid);
        (async () => {
          try {
            const res = await agentApi.sessions.chat(
              sid,
              pendingMsg,
              modelRoute ? Number(modelRoute) : undefined,
              undefined,
              runMode,
            );
            if (res?.runId) {
              currentRunIdRef.current = res.runId;
              runUserMessageRef.current.set(res.runId, pendingMsg);
              const rid = res.runId;
              setMessages((prev) => {
                const idx = prev.findLastIndex(
                  (m: any) => m.role === 'user' && m.runId == null,
                );
                if (idx < 0) return prev;
                return prev.map((m, i) =>
                  i === idx ? { ...(m as any), runId: rid } : m,
                );
              });
            }
            // Fetch the latest timeline once immediately after chat succeeds (user row + assistant
            // container row),
            // avoiding reliance on SSE/terminal events alone (empty cursors were previously skipped)
            void refreshLatest(sid);
          } catch (e) {
            setSending(false);
            setMessages((prev) => [
              ...prev,
              { role: 'ai', content: `Error: ${e}`, ts: Date.now() },
            ]);
          }
        })();
      } else {
        setSending(false);
        const s = sessions.find((x: any) => x.id === sid);
        if (s) {
          setCurrentSession(s);
          setMessages([]);
        } else {
          agentApi.sessions
            .get(sid)
            .then(setCurrentSession)
            .catch(() => {
              if (!trainingMode) navigate('/chat', { replace: true });
            });
        }
        setSelectedModelRouteId(undefined);
        reasoningTouchedRef.current = false;
        setReasoningCapability(null);
        setReasoningEffort(undefined);
        void refreshReasoningCapability(sid);
        setHasMoreHistory(true);
        historyPageRef.current = 1;
        setMessages([]);
        reconnectCountRef.current = 0;
        runUserMessageRef.current = new Map();
        setTodos([]);
        setToolCalls([]);
        setPreparingTool(null);
        if (!trainingMode) setSessionPanelOpen(true);
        // Unified Timeline: reset and load the first and last page (each page is controlled by backend keyset paging)
        setTimeline([]);
        oldestSeqRef.current = null;
        newestSeqRef.current = null;
        setTimelineHasMore(false);
        void loadInitialTimeline(sid);
        agentApi.sessions
          .getTodos(sid)
          .then((data: any) => {
            if (Array.isArray(data)) setTodos(data);
          })
          .catch(() => {});
        agentApi.sessions
          .getLatestToolCalls(sid)
          .then((data: any) => {
            if (Array.isArray(data)) {
              setToolCalls(
                data.map((r: any) => ({
                  name: r.displayNameCn || r.displayName || r.toolName || '',
                  displayNameCn: r.displayNameCn || r.displayName || '',
                  displayNameEn: r.displayNameEn || '',
                  status:
                    r.status === TOOL_CALL_RECORD_STATUS.SUCCEEDED
                      ? 'succeeded'
                      : r.status === TOOL_CALL_RECORD_STATUS.FAILED
                        ? 'failed'
                        : 'pending',
                  _publishId: `tool-${r.id}`,
                  _runId: r.runId,
                  ts: r.createdAt ? toTs(r.createdAt) : Date.now(),
                })),
              );
            }
          })
          .catch(() => {});
        loadHistory(sid, 1);
      }
    } else {
      resetChatView();
    }
    return () => {
      sseStoppedRef.current = true;
      if (reconnectTimerRef.current != null) {
        window.clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      if (abortRef.current) {
        abortRef.current.abort();
        abortRef.current = null;
      }
    };
  }, [sessionId]);

  useEffect(() => {
    if (
      currentSession?.agentInstanceId &&
      instances.length > 0 &&
      !selectedModelRouteId
    ) {
      const instance = instances.find(
        (i: any) => i.id === currentSession.agentInstanceId,
      );
      if (instance?.modelRouteId) {
        setSelectedModelRouteId(instance.modelRouteId);
      }
    }
  }, [currentSession?.agentInstanceId, instances, selectedModelRouteId]);

  const loadMoreHistory = () => {
    if (!hasMoreHistory || !sessionId) return;
    loadHistory(Number(sessionId), historyPageRef.current);
  };

  const handleCancelClarification = useCallback(
    (clarification: any) => {
      agentApi.sessions
        .clarify(
          clarification.sessionId,
          clarification.runId,
          '__cancel__',
          clarification.clarificationId,
        )
        .catch(() => {});
      if (currentSession?.id && clarification.runId) {
        agentApi.runs
          .stop(currentSession.id, clarification.runId)
          .catch(() => {});
        // Release this clarification row locally right away (without waiting for SSE) and reset the send state
        setTimeline((prev) =>
          applyClarificationState(
            prev,
            {
              runId: clarification.runId,
              clarificationId: clarification.clarificationId,
            },
            'CANCELLED',
          ),
        );
        setSending(false);
      }
    },
    [currentSession],
  );

  const sendMessage = async (attachmentKeys?: string[]) => {
    const hasAttachment =
      Array.isArray(attachmentKeys) && attachmentKeys.length > 0;
    if (
      (!inputValue.trim() && !hasAttachment) ||
      !currentSession ||
      !runtimeState?.canSend
    ) {
      return;
    }

    // Cancel any pending clarification before sending a new message (source tables only: the batch awaiting clarification)
    const pendingBatch = clarificationBatch;
    const pendingItems = pendingBatch?.items ?? [];
    if (pendingBatch) {
      for (const c of pendingItems) {
        agentApi.sessions
          .clarify(
            currentSession.id,
            pendingBatch.runId,
            '__cancel__',
            c.clarificationId,
          )
          .catch(() => {});
      }
    }
    if (pendingItems.length > 0) cancelPendingClarificationsLocally();

    setMessages((prev) => [
      ...prev,
      { role: 'user', content: inputValue, ts: Date.now() },
    ]);
    const msg = inputValue;
    setInputValue('');
    setSending(true);
    try {
      const res = await agentApi.sessions.chat(
        currentSession.id,
        msg,
        selectedModelRouteId,
        attachmentKeys,
        runMode,
        reasoningEffort,
      );
      if (res?.status === STATUS.RUN.QUEUED) {
        setQueuedMessages((prev) => [
          ...prev,
          { queueId: res.queueId, text: msg, createdAt: Date.now() },
        ]);
        setSending(false);
        return;
      }
      if (res?.runId) {
        currentRunIdRef.current = res.runId;
        runUserMessageRef.current.set(res.runId, msg);
        // The optimistic user bubble only gets the backend runId now: write it back so ordering can anchor on the run (backend is authoritative)
        const rid = res.runId;
        setMessages((prev) => {
          const idx = prev.findLastIndex(
            (m: any) => m.role === 'user' && m.runId == null,
          );
          if (idx < 0) return prev;
          return prev.map((m, i) =>
            i === idx ? { ...(m as any), runId: rid } : m,
          );
        });
        // Unified Timeline: fetch the latest page to append user rows/assistant containers
        if (currentSession?.id) void refreshLatest(Number(currentSession.id));
      }
    } catch (err) {
      setSending(false);
      setMessages((prev) => [
        ...prev,
        { role: 'ai', content: `Error: ${err}`, ts: Date.now() },
      ]);
    }
  };

  const landingCreateSession = async () => {
    if (!inputValue.trim()) return;
    const firstAvailable = instances.find((i: any) => i.modelRouteId);
    const instanceId =
      chosenInstance || (firstAvailable ? String(firstAvailable.id) : null);
    if (!instanceId) {
      message.warning('Please select an instance');
      return;
    }
    const msg = inputValue;
    setInputValue('');
    setSending(true);
    try {
      const vo = await agentApi.sessions.create({
        title: `Chat ${new Date().toLocaleString()}`,
        agentInstanceId: Number(instanceId),
        overrideModelRouteId: selectedModelRouteId ?? undefined,
      });
      setSessions((prev) => [vo, ...prev]);
      setChosenInstance(String(instanceId));
      sessionStorage.setItem('landingPendingMsg', msg);
      if (selectedModelRouteId)
        sessionStorage.setItem(
          'landingModelRouteId',
          String(selectedModelRouteId),
        );
      history.push(`/chat/${vo.id}`);
    } catch (e: any) {
      setSending(false);
      const errMsg = localizeApiError(
        e?.data,
        e?.message || 'Failed to create session',
      );
      message.error(errMsg);
    }
  };

  const deleteSession = async (id: number) => {
    try {
      await agentApi.sessions.close(id);
      if (currentSession?.id === id) {
        setCurrentSession(null);
        setMessages([]);
        navigate('/chat', { replace: true });
      }
    } catch {}
    setSessions((prev) => prev.filter((s: any) => s.id !== id));
  };

  const handleSessionRename = async (id: number, title: string) => {
    await agentApi.sessions.update(id, title.trim());
    setSessions((prev) =>
      prev.map((s: any) => (s.id === id ? { ...s, title: title.trim() } : s)),
    );
    if (currentSession?.id === id) {
      setCurrentSession((prev: any) =>
        prev ? { ...prev, title: title.trim() } : prev,
      );
    }
  };

  const handleBatchDelete = async (ids: number[]) => {
    await agentApi.sessions.batchClose(ids);
    setSessions((prev) => prev.filter((s: any) => !ids.includes(s.id)));
    if (currentSession && ids.includes(currentSession.id)) {
      setCurrentSession(null);
      setMessages([]);
      navigate('/chat');
    }
  };

  const refreshInstances = useCallback(() => {
    agentApi.instances
      .listLatest(3)
      .then((data) => {
        const sorted = [...data].sort((a, b) => {
          if (a.modelRouteId && !b.modelRouteId) return -1;
          if (!a.modelRouteId && b.modelRouteId) return 1;
          return (
            new Date(b.updatedAt || b.createdAt).getTime() -
            new Date(a.updatedAt || a.createdAt).getTime()
          );
        });
        setInstances(sorted);
      })
      .catch(() => {});
  }, []);

  const content = (
    <>
      <div
        className={
          trainingMode
            ? `${styles.layout} ${styles.trainingLayout}`
            : styles.layout
        }
      >
        {!trainingMode ? (
          <div className={styles.sidebarWrapper}>
            <div
              className={`${styles.sidebar} ${sidebarCollapsed ? styles.sidebarCollapsed : ''}`}
            >
              <Sidebar
                sessions={sessions}
                currentSession={currentSession}
                hasMoreSessions={hasMoreSessions}
                onLoadMore={() => loadSessions(sessionOffset)}
                onSelect={(id) => navigate(`/chat/${id}`)}
                onDelete={deleteSession}
                onRename={handleSessionRename}
                onBatchDelete={handleBatchDelete}
                onNewChat={() => navigate('/chat')}
                conversationScope={conversationScope}
                onConversationScopeChange={(next) => {
                  persistChatPreference(CHAT_SCOPE_STORAGE_KEY, next);
                  setConversationScope(next);
                  resetChatView();
                }}
                userRole={userRole}
                onUserRoleChange={(next) => {
                  persistChatPreference(CHAT_ROLE_STORAGE_KEY, next);
                  setUserRole(next);
                  resetChatView();
                }}
                canViewTenant={canViewTenant}
                readOnly={
                  !!currentSession &&
                  (currentSession.readOnly ??
                    READ_ONLY_SESSION_TYPES.has(currentSession.sessionType))
                }
              />
            </div>
            <div
              className={styles.sidebarToggle}
              onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
            >
              {sidebarCollapsed ? <RightOutlined /> : <LeftOutlined />}
            </div>
          </div>
        ) : null}
        <div className={styles.main}>
          {currentSession ? (
            <ChatMain
              currentSession={currentSession}
              instances={instances}
              trainingMode={trainingMode}
              hideSessionPanel={trainingMode}
              readOnly={
                !!currentSession &&
                (currentSession.readOnly ??
                  READ_ONLY_SESSION_TYPES.has(currentSession.sessionType))
              }
              selectedModelRouteId={selectedModelRouteId}
              onModelRouteChange={(id) => {
                setSelectedModelRouteId(id);
                if (currentSession?.id) {
                  void refreshReasoningCapability(currentSession.id, id);
                }
              }}
              modelRoutes={modelRoutes}
              reasoningCapability={reasoningCapability}
              reasoningEffort={reasoningEffort}
              onReasoningChange={(level) => {
                reasoningTouchedRef.current = true;
                setReasoningEffort(level);
              }}
              sseConnected={sseConnected}
              messages={messages}
              hasMoreHistory={hasMoreHistory}
              onLoadMoreHistory={loadMoreHistory}
              inputValue={inputValue}
              onInputValueChange={setInputValue}
              sending={sending}
              onSendMessage={sendMessage}
              onCancelSend={() => {
                // Cancel any pending clarification first (source tables only: the batch awaiting clarification) and unlock locally right away
                const pendingBatch = clarificationBatch;
                const pendingItems = pendingBatch?.items ?? [];
                if (pendingBatch) {
                  for (const c of pendingItems) {
                    if (currentSession?.id) {
                      agentApi.sessions
                        .clarify(
                          currentSession.id,
                          pendingBatch.runId,
                          '__cancel__',
                          c.clarificationId,
                        )
                        .catch(() => {});
                    }
                  }
                }
                cancelPendingClarificationsLocally();
                // sending is not optimistically unlocked: wait for the run's terminal event to restore it; an 8s fallback covers a backend that never emits a terminal state
                if (stopFallbackRef.current) {
                  clearTimeout(stopFallbackRef.current);
                }
                stopFallbackRef.current = window.setTimeout(() => {
                  stopFallbackRef.current = null;
                  setSending(false);
                }, 8000);
                if (currentSession?.id) {
                  agentApi.runs
                    .sessionStop(currentSession.id)
                    .catch((e: any) => {
                      // No longer silently swallow errors: a failed session ownership check (FORBIDDEN), etc., would make "stop" appear to do nothing.
                      console.warn('session stop failed', e);
                      message.error(
                        e?.message
                          ? intl.formatMessage(
                              {
                                id: 'pages.chat.stopFailed',
                                defaultMessage: 'Stop failed: {message}',
                              },
                              { message: e.message },
                            )
                          : intl.formatMessage({
                              id: 'pages.chat.stopFailedRetry',
                              defaultMessage: 'Stop failed, please retry',
                            }),
                      );
                    });
                }
              }}
              onCancelClarification={handleCancelClarification}
              onExpandOpen={() => {
                setExpandText(inputValue);
                setExpandOpen(true);
              }}
              sessionPanelOpen={sessionPanelOpen}
              onTogglePanel={() => setSessionPanelOpen(!sessionPanelOpen)}
              timeline={timeline}
              runtimeState={runtimeState}
              timelineHasMore={timelineHasMore}
              onLoadOlderTimeline={loadOlderTimeline}
              onRespondClarify={handleTimelineClarify}
              subAgentLive={subAgentLive}
              preparingTool={preparingTool}
              runMode={runMode}
              onRunModeChange={handleRunModeChange}
              queuedMessages={queuedMessages}
              onWithdrawQueued={withdrawQueued}
              toolApprovalBatch={toolApprovalBatch}
              toolApprovalSubmitting={toolApprovalSubmitting}
              onResolveToolApproval={resolveToolApproval}
              clarificationBatch={clarificationBatch}
              clarificationSubmitting={clarificationSubmitting}
              onResolveClarifications={resolveClarifications}
              onCancelClarifications={() => {
                if (currentSession?.id) {
                  setClarificationBatch(null);
                  agentApi.runs.sessionStop(currentSession.id).catch(() => {});
                }
              }}
              loadSubAgentSteps={async (id: number) => {
                try {
                  return await agentApi.sessions.getSubAgentTimeline(id);
                } catch {
                  return [];
                }
              }}
            />
          ) : trainingMode ? (
            <div
              style={{
                flex: 1,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#8c8c8c',
                fontSize: 13,
              }}
            >
              {intl.formatMessage({
                id: 'pages.chat.loadingSession',
                defaultMessage: 'Loading session…',
              })}
            </div>
          ) : (
            <Landing
              instances={instances}
              chosenInstance={chosenInstance}
              currentInstanceObj={currentInstanceObj}
              onInstanceChange={(inst: any) => {
                if (!inst || !inst.modelRouteId) return;
                setChosenInstance(String(inst.id));
                setSelectedModelRouteId(inst.modelRouteId);
                setCurrentInstanceObj(inst);
              }}
              inputValue={inputValue}
              onInputValueChange={setInputValue}
              sending={sending}
              onStartSession={landingCreateSession}
              onOpenInstanceDrawer={() => setInstanceDrawerOpen(true)}
              onCreateInstance={() =>
                navigate('/instances', { state: { openCreate: true } })
              }
              onConfigureModelRoute={(inst: any) => {
                setConfigModelInstance(inst);
              }}
            />
          )}
        </div>
        {!trainingMode ? (
          <SessionPanel
            open={sessionPanelOpen}
            onClose={() => setSessionPanelOpen(false)}
            todos={todos}
            toolStats={toolStats}
            sessionUsage={sessionUsage}
            maxInputTokens={
              modelRoutes.find((r: any) => r.id === selectedModelRouteId)
                ?.maxInputTokens
            }
          />
        ) : null}
      </div>
      <ExpandModal
        open={expandOpen}
        onClose={() => setExpandOpen(false)}
        onOk={() => {
          setInputValue(expandText);
          setExpandOpen(false);
        }}
        text={expandText}
        onTextChange={setExpandText}
      />
      <InstanceDrawer
        open={instanceDrawerOpen}
        onClose={() => setInstanceDrawerOpen(false)}
        onSelect={(inst) => {
          setInstanceDrawerOpen(false);
          if (!inst || !inst.modelRouteId) return;
          setChosenInstance(String(inst.id));
          setSelectedModelRouteId(inst.modelRouteId);
          setCurrentInstanceObj(inst);
        }}
        selectedId={chosenInstance}
      />
      <SetModelRouteModal
        open={!!configModelInstance}
        instance={configModelInstance}
        onClose={() => {
          setConfigModelInstance(null);
        }}
        onSuccess={() => {
          refreshInstances();
        }}
      />
    </>
  );

  if (trainingMode) {
    return <div className={styles.trainingRoot}>{content}</div>;
  }

  return (
    <PageContainer
      title={false}
      breadcrumbRender={false}
      ghost
      style={{ paddingBlock: 0, margin: 0 }}
      header={{ title: undefined, style: { display: 'none' } }}
      childrenContentStyle={{
        paddingBlock: 0,
        paddingInline: 24,
        height: 'calc(100vh - 160px)',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
      }}
    >
      {content}
    </PageContainer>
  );
}

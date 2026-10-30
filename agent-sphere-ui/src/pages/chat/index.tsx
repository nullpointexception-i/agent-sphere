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
import {
  DOCWRITE_TOOL_NAME,
  TODOWRITE_TOOL_NAME,
  TOOL_CALL_RECORD_STATUS,
} from '@/constants/toolCall';
import { agentApi } from '@/services/agentSphere/api';
import { getToken } from '@/utils/auth';
import { connectSse } from '@/utils/sse';
import ChatMain from './components/chat';
import { stripSubAgentSentinel } from './components/chat/subAgentMarker';
import type { SubAgentLiveMap } from './components/chat/subAgentTypes';
import ExpandModal from './components/ExpandModal';
import InstanceDrawer from './components/InstanceDrawer';
import Landing from './components/Landing';
import SessionPanel from './components/SessionPanel';
import type { ClarificationBatch } from './components/ClarificationDrawer';
import type { ToolApprovalBatch } from './components/ToolApprovalDrawer';
import { SESSION_USAGE_REFRESH_EVENT } from './components/Usage';
import Sidebar from './components/Sidebar';
import { useStyles } from './style';

const SESSION_PAGE_SIZE = 10;

/**
 * 澄清乐观态的本地保护键。必须包含 clarificationId：同一次 run 续跑时后端复用 runId，
 * 而每轮追问的 clarificationId 不同。只用 runId 会让「第二轮澄清」被误判成已应答。
 * clarificationId 缺失时返回 null —— 调用方据此【放弃保护】、采信服务端状态，
 * 因为错误拦截的代价（永久显示错误状态直到刷新）远大于放过一次竞态回退。
 */
function clarificationKey(runId: unknown, clarificationId: unknown): string | null {
  if (runId == null || clarificationId == null || clarificationId === '') return null;
  return `${runId}:${clarificationId}`;
}

/** 兼容后端 `YYYY-MM-DD HH:mm:ss[.ffffff]`（非 ISO）与 ISO 格式 → timestamp；失败返回 0。 */
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

export default function Chat() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const intl = useIntl();
  const { message, modal } = App.useApp();
  const { styles } = useStyles();

  const [sessions, setSessions] = useState<any[]>([]);
  const [sessionOffset, setSessionOffset] = useState(0);
  const [hasMoreSessions, setHasMoreSessions] = useState(true);
  const [currentSession, setCurrentSession] = useState<any>(null);
  const [messages, setMessages] = useState<any[]>([]);
  const [timeline, setTimeline] = useState<any[]>([]);
  const [timelineHasMore, setTimelineHasMore] = useState(false);
  const [subAgentLive, setSubAgentLive] = useState<SubAgentLiveMap>({});
  const oldestSeqRef = useRef<number | null>(null);
  const newestSeqRef = useRef<number | null>(null);
  const [inputValue, setInputValue] = useState('');
  const [sending, setSending] = useState(false);
  const [hasMoreHistory, setHasMoreHistory] = useState(true);
  const [sseConnected, setSseConnected] = useState(false);
  const [sessionUsage, setSessionUsage] = useState<any>(null);
  const [toolStats, setToolStats] = useState<any>(null);
  const [runMode, setRunMode] = useState<'WRITE' | 'READ_ONLY'>('WRITE');
  const [toolApprovalBatch, setToolApprovalBatch] = useState<ToolApprovalBatch | null>(null);
  const [toolApprovalSubmitting, setToolApprovalSubmitting] = useState(false);
  const [clarificationBatch, setClarificationBatch] = useState<ClarificationBatch | null>(null);
  const [clarificationSubmitting, setClarificationSubmitting] = useState(false);
  const [instances, setInstances] = useState<any[]>([]);
  const [chosenInstance, setChosenInstance] = useState('');
  const [currentInstanceObj, setCurrentInstanceObj] = useState<any>(null);
  const [modelRoutes, setModelRoutes] = useState<any[]>([]);
  const [selectedModelRouteId, setSelectedModelRouteId] = useState<
    number | undefined
  >(undefined);
  const [expandOpen, setExpandOpen] = useState(false);
  const [expandText, setExpandText] = useState('');
  const [instanceDrawerOpen, setInstanceDrawerOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [configModelInstance, setConfigModelInstance] = useState<any>(null);
  const [todos, setTodos] = useState<any[]>([]);
  const [toolCalls, setToolCalls] = useState<any[]>([]);
  const [sessionPanelOpen, setSessionPanelOpen] = useState(false);

  const historyPageRef = useRef(1);
  const abortRef = useRef<AbortController | null>(null);
  const currentSessionIdRef = useRef<number | null>(null);
  const reconnectCountRef = useRef(0);
  const runUserMessageRef = useRef<Map<number, string>>(new Map());
  const currentRunIdRef = useRef<number | null>(null);
  const stopFallbackRef = useRef<number | null>(null);
  const pendingMsgConsumedRef = useRef(false);
  // 已本地应答的澄清：防止随后 refreshLatest 用服务端旧 PENDING 覆盖乐观态。
  // 键必须是 runId + clarificationId，不能只用 runId —— 后端 resumeFromClarification
  // 在 run 处于 AWAITING_USER 时会【复用同一个 runId】续跑，一次会话里 agent 可能被
  // 追问多轮，各轮 clarificationId 不同却共用 runId。按 runId 加键会把后续澄清全部
  // 误判为「已答过」并改写成 ANSWERED，待选项和输入框不渲染（刷新后 Ref 重建才恢复）。
  const answeredClarificationsRef = useRef<Set<string>>(new Set());

  const loadSessions = useCallback(async (offset: number) => {
    try {
      const list = await agentApi.sessions.list(offset, SESSION_PAGE_SIZE);
      if (offset === 0) {
        setSessions(list);
      } else {
        setSessions((prev) => [...prev, ...list]);
      }
      setSessionOffset(offset + list.length);
      if (list.length < SESSION_PAGE_SIZE) setHasMoreSessions(false);
    } catch {}
  }, []);

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
    loadSessions(0);
  }, [loadSessions]);

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
        // 澄清应答 run：回复已展示在澄清卡片里，跳过独立用户气泡
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
        // 澄清不再写入 messages：澄清卡由 timeline 渲染，messages 仅保留用户气泡。
      }
      if (page === 1) {
        setMessages(historyMsgs);
      } else {
        setMessages((prev) => [...historyMsgs, ...prev]);
      }
    } catch {}
  }, []);

  // 按 runId（可选 clarificationId）就地更新 timeline 澄清行状态
  // 把某次澄清的状态变更应用到匹配行。
  // 匹配粒度：clarificationId 存在时【必须】命中同一轮；缺失时只允许命中 run 内
  // 仍处于 PENDING 的行 —— 否则同 run 续跑产生的后续澄清会被连带改写。
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
      } else if (r.state !== 'PENDING') {
        // 无 id 可比时只改待答复的行，绝不覆盖已终结的行
        return r;
      }
      return {
        ...r,
        state,
        content: { ...r.content, ...(response != null ? { response } : {}) },
      };
    });

  // 本地立即解除所有 PENDING 澄清（停止/取消时秒解锁，不等 SSE 回来）
  const cancelPendingClarificationsLocally = () =>
    setTimeline((prev) =>
      prev.map((r) =>
        r.kind === 'clarification' && r.state === 'PENDING'
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
          // 子 Agent 权威行到达时移除 SS E 占位行（seq<0），避免同 subId 出现双行
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
          // 已本地应答的澄清不被服务端旧 PENDING 回退。按 clarificationId 匹配，
          // 缺 id 时不拦截（见 clarificationKey 注释）。
          if (incoming.kind === 'clarification' && incoming.state === 'PENDING') {
            const key = clarificationKey(incoming.runId, incoming.content?.clarificationId);
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
      // 后端未启用/异常：忽略
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
    // afterSeq 分页在后端是 `LIMIT size+1`（AgentTimelineServiceImpl#list）。若两次刷新
    // 之间新增超过 limit 行，单次只会补到第 limit 行，剩余行被 hasMore 标记但没有任何
    // 「继续向后拉」的路径（loadOlderTimeline 走 beforeSeq，方向相反）→ 中间行永久缺失
    // 到下次刷新。这里循环续拉，直到取空或不再 hasMore；轮次上限防异常情况下死循环。
    const LIMIT = 10;
    const MAX_ROUNDS = 20;
    let round = 0;
    let more = false;
    do {
      try {
        // 空游标（新会话首条消息）→ 直接拉尾页；否则按 afterSeq 增量补行
        const page = await agentApi.sessions.getTimeline(
          sid,
          newestSeqRef.current != null
            ? { afterSeq: newestSeqRef.current, limit: LIMIT }
            : { limit: LIMIT },
        );
        const rows = Array.isArray(page?.rows) ? page.rows : [];
        const before = newestSeqRef.current;
        if (rows.length) {
          mergeTimeline(rows);
          bumpCursors(rows);
        }
        more = !!page?.hasMore;
        if (more) setTimelineHasMore(true);
        // 游标未前进（空页，或后端返回的 seq 不大于当前游标）→ 无更多可取，直接终止，
        // 否则同一页会被反复拉取直到 MAX_ROUNDS。
        if (!rows.length || newestSeqRef.current === before) break;
      } catch {
        break;
      }
    } while (more && ++round < MAX_ROUNDS);
  };

  // 按最新 run 状态行校准发送锁。SSE 断连窗口内若丢了 run 终态事件，sending 会一直卡在
  // true（此前只能刷新页面恢复）；重连后拉一次权威状态即可解锁。
  // 停车态判定：RUNNING/PENDING/WAITING 仍锁定；终态与 AWAITING_USER 解锁
  // （AWAITING_USER 由澄清卡接管，输入框应可用来作答）。
  const reconcileSendLock = async (sid: number) => {
    try {
      const page = await agentApi.sessions.getTimeline(sid, { limit: 30 });
      if (currentSessionIdRef.current !== sid) return;
      const rows = Array.isArray(page?.rows) ? page.rows : [];
      const runRows = rows.filter((r: any) => r.kind === 'run_status');
      const last = runRows[runRows.length - 1];
      if (!last) return;
      const st = String(last.state || '');
      setSending(st === 'RUNNING' || st === 'PENDING' || st === 'WAITING');
      if (st === 'AWAITING_USER') {
        void refreshPendingToolApproval(sid);
        void refreshPendingClarifications(sid);
      }
    } catch {
      /* ignore */
    }
  };

  // 工具事件后的整页覆盖刷新：按 seq 覆盖同 seq 行，补齐迟到落库的 content（如截图 images）。
  // afterSeq 增量会跳过 seq ≤ newest 的既有行（工具行），导致 live 阶段看不到截图；此处拉尾窗覆盖修正。
  const refreshTail = async (sid: number) => {
    try {
      const page = await agentApi.sessions.getTimeline(sid, { limit: 30 });
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

  // 会话级用量：run 终态后拉取 SUM，驱动聊天区最下方吸底用量条
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
      const stats = await agentApi.sessions.toolStats(sid, currentRunIdRef.current);
      setToolStats(stats);
    } catch {
      // ignore
    }
  };

  useEffect(() => {
    setToolStats(null);
    if (currentSession?.id) {
      void refreshSessionUsage(currentSession.id);
      void refreshToolStats(currentSession.id);
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
          s.id === currentSession.id ? { ...s, runMode: updated?.runMode || mode } : s,
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
    return () => window.removeEventListener(SESSION_USAGE_REFRESH_EVENT, handler);
  }, []);

  const handleTimelineClarify = async (row: any, response: string) => {
    if (!currentSession?.id || !row?.runId) return;
    const key = clarificationKey(row.runId, row.clarificationId);
    // 先记为已应答，防止随后的 refreshLatest 用服务端旧 PENDING 覆盖乐观态。
    // id 缺失时不记录：无法定位到具体是哪一轮澄清，记 runId 会误伤同 run 的后续澄清。
    if (key) answeredClarificationsRef.current.add(key);
    // 乐观回显：立即将澄清行置为已应答并显示用户提交内容
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
      // 提交失败（重复提交/澄清已失效）：撤销本地应答并拉取权威状态，避免卡死
      if (key) answeredClarificationsRef.current.delete(key);
      message.error(e?.message || '澄清提交失败，请重试');
      void refreshLatest(currentSession.id);
    }
  };

  // 子 Agent 实时步骤：由 SSE 事件增量构建（纯驱动，无需轮询/节流拉接口）。
  // - reasoning_token 首帧(firstFrame) → 切新 LLM 轮；其余 → 追加当前轮思考；
  // - content_token → 追加当前轮回复；
  // - tool_call_{started,in_progress,succeeded,failed} → 按 publishId 就地更新工具步骤。
  /** 从工具结果 artifact JSON 解析浏览器截图引用（data.screenshot.fileKey/contentType）。 */
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
        // 工具结果含浏览器截图 → live 步骤直接带 images（刷新后走后端 timeline 一致）
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
          // 新 LLM 轮：剥离首帧哨兵行（"<marker>id: name\n"），旧轮标记结束
          // 新旧前缀均接受（`▶ Agent ` / `▶ Skill `），避免历史会话断裂
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

  const parseToolApproval = useCallback((payload: any): ToolApprovalBatch | null => {
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
  }, []);

  const refreshPendingToolApproval = useCallback(async (sid: number) => {
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
        })),
      });
    } catch {
      // keep current drawer on transient errors
    }
  }, []);

  const resolveClarifications = async (
    answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
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
      message.error(e?.message || '澄清提交失败，请重试');
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

  // SSE 事件可能丢失或在连接建立前发出：run 活跃期间做短时有界轮询，
  // 确保审批/澄清浮层不依赖单一路径也能出现。
  useEffect(() => {
    if (!currentSession?.id || !sending) return;
    const sid = currentSession.id;
    const timer = window.setInterval(() => {
      void refreshPendingToolApproval(sid);
      void refreshPendingClarifications(sid);
    }, 2000);
    const stop = window.setTimeout(() => window.clearInterval(timer), 30000);
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
    if (abortRef.current) abortRef.current.abort();
    // 切会话时清空本地已应答标记（仅内存，避免跨会话误抑制刷新）
    if (currentSessionIdRef.current !== sid) {
      answeredClarificationsRef.current.clear();
    }
    currentSessionIdRef.current = sid;
    setSseConnected(false);
    reconnectCountRef.current = 0;

    const controller = new AbortController();
    abortRef.current = controller;
    const token = getToken();
    if (!token) {
      // token 瞬时为空时不要静默放弃：1s 后重试，避免 SSE 永久不建连
      setTimeout(() => {
        if (currentSessionIdRef.current === sid) connectSSE(sid);
      }, 1000);
      return;
    }

    connectSse(
      `/api/v1/runtime/${sid}/stream`,
      token,
      {
        onOpen: () => {
          const wasReconnect = reconnectCountRef.current > 0;
          setSseConnected(true);
          reconnectCountRef.current = 0;
          console.log('[SSE] connected');
          if (wasReconnect) {
            // 重连：补拉断连窗口内丢失的行，并按权威 run 状态校准发送锁，
            // 否则丢终态事件会让发送/停止永久锁死（只能刷新页面恢复）。
            void refreshLatest(sid);
            void reconcileSendLock(sid);
          }
          void refreshPendingToolApproval(sid);
          void refreshPendingClarifications(sid);
        },
        onMessage: (payload) => {
          try {
            const parsed = JSON.parse(payload);
            const evtType = parsed.eventType || parsed.type || '';
            const d = parsed.data || parsed;

            // 会话守卫：忽略非当前会话的事件，防止旧会话仍在流式时污染当前 messages/timeline（串台/重复）
            if (currentSessionIdRef.current !== sid) return;

            if (evtType === 'TOOL_APPROVAL_PENDING') {
              const batch = parseToolApproval(d);
              if (batch) setToolApprovalBatch(batch);
              void refreshLatest(sid);
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
              void refreshLatest(sid);
              return;
            }

            // 子 Agent 实时：纯 SSE 驱动逐步构建（content/reasoning/tool 事件均带 subAgentRunId）
            if (d?.subAgentRunId != null) {
              handleSubAgentLiveEvent(evtType, d);
              // 头行兜底：纯 LLM 式子 run 期间没有 run 终态/tool 结束事件触发 refreshLatest，
              // 若子 Agent 头行还没进 timeline，SubAgentCard 就不会挂载 → live 无从渲染。
              // 此处就地插入 RUNNING 占位行（后续 refreshLatest/终态合并自动替换为权威行）。
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
                      // 占位 seq 按 subId 唯一取负（与 widget placeholderSeq 对齐）：
                      // 并行子 agent 会同时存在多个占位行，硬编码 -1 会导致 React key
                      // `-1-subagent` 重复，且 mergeTimeline 按 seq 建 Map 时互相覆盖丢数据
                      seq: -(Math.abs(liveSubId) + 1),
                      kind: 'subagent',
                      refSubAgentRunId: liveSubId,
                      status: 'RUNNING',
                      title: d?.displayNameCn || d?.displayName || '子 Agent',
                      content: {
                        displayName: d?.displayNameCn || d?.displayName,
                        state: 'RUNNING',
                        // 同批次并行子 agent 共享 parentToolCallId，用于 Tab 成组展示
                        parentToolCallId: d?.parentToolCallId ?? null,
                      },
                    },
                  ].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
                });
              }
            }

            // 统一 Timeline 流式/补行（后端信封已带 seq/kind）：
            // - assistant 容器 token（content/reasoning）→ 按 seq 就地追加打则字；
            // - run 终态/工具结束/澄清 → 按 afterSeq 拉最新页补行（工具/澄清行暂无 seq 打标）。
            const tlSubType = String(
              d?.reasoningSubType || d?.status || evtType,
            );

            // run 运行态/终态：顶层兜底解锁/置位，不依赖 reasoning_token 信封，
            // 避免终态事件以其他信封到达时 sending 永久卡住。
            if (
              tlSubType === 'run_completed' ||
              tlSubType === 'run_failed' ||
              tlSubType === 'run_cancelled' ||
              tlSubType === 'run_awaiting_user'
            ) {
              setSending(false);
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
              // wait 挂起：run 并未结束，到点会自行恢复，输入框应保持锁定。
              // 断线重连恰好落在等待窗口内时，靠这条把 sending 重新立起来。
              tlSubType === 'run_waiting'
            ) {
              setSending(true);
            }

            // 上下文压缩：RUNNING 事件本地插行（后端已回填 seq/kind），输出 token 追加到正文。
            // 刻意不在 compaction_running 时 refreshLatest —— 浅合并会用「尚无正文」的权威行覆盖已流出的文字。
            if (d?.seq != null && d?.kind === 'compaction') {
              const seq = Number(d.seq);
              const delta = evtType === 'content_token' ? String(d?.response || '') : '';
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
                    d?.assistantReply ||
                    existing?.title ||
                    '正在压缩上下文…',
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
              const field = evtType === 'content_token' ? 'reply' : 'thinking';
              const delta = String(d.response);
              setTimeline((prev) => {
                const m = new Map<number, any>(prev.map((r) => [r.seq, r]));
                const seq = Number(d.seq);
                const existing = m.get(seq);
                if (existing) {
                  m.set(seq, {
                    ...existing,
                    content: {
                      ...(existing.content || {}),
                      [field]:
                        ((existing.content && existing.content[field]) || '') +
                        delta,
                    },
                  });
                } else {
                  // 行尚不存在（新会话首条消息等，refreshLatest 此前空游标直接返回）
                  // → 插入占位 assistant 行，后续按 afterSeq 拉取/终态时 mergeTimeline 补齐全文
                  m.set(seq, {
                    seq,
                    kind: 'assistant',
                    status: 'RUNNING',
                    content: { [field]: delta },
                  });
                }
                return [...m.values()].sort((a, b) => a.seq - b.seq);
              });
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
              // 工具事件：整页覆盖刷新（补迟到落库的截图 images 等），避免 afterSeq 增量跳过既有工具行
              if (
                tlSubType === 'tool_call_succeeded' ||
                tlSubType === 'tool_call_failed'
              ) {
                void refreshTail(sid);
              } else {
                void refreshLatest(sid);
              }
            }
            // run 终态：刷新会话级用量（聊天区吸底 SUM）
            if (
              tlSubType === 'run_completed' ||
              tlSubType === 'run_failed' ||
              tlSubType === 'run_cancelled'
            ) {
              void refreshSessionUsage(sid);
            }

            // 始终跟踪当前 runId（含任务系统发起的 run），保证停止能命中正确 run
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
                if (currentSession?.id) void refreshToolStats(currentSession.id);
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
                      const docInfo = JSON.parse(d?.artifact || '{}');
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
                if (currentSession?.id) void refreshToolStats(currentSession.id);
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
                if (currentSession?.id) void refreshToolStats(currentSession.id);
                if (subType === 'run_awaiting_user') {
                  void refreshPendingToolApproval(sid);
                  void refreshPendingClarifications(sid);
                }
                if (stopFallbackRef.current) {
                  clearTimeout(stopFallbackRef.current);
                  stopFallbackRef.current = null;
                }
                const runId = d?.runId;
                // 展示 LLM 错误消息（429/502/其他）
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
                // 注意：不再用 assistantReply 聚合生成独立 AI 回复气泡；
                // 主 Agent 回复只显示在各 interaction timeline 条目内（content_token 归组）。
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

              // 推理已改为后端 Timeline/TimelineList 展示，不再维护 interaction timeline 拼装；
              // 剩余 isSystem 状态行（run_pending 等）忽略；intent_retry 时清理空 ai 消息。
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
              if (evtType === 'clarification_responded') {
                // 记为已应答：防止随后 refreshLatest 用服务端旧 PENDING 覆盖乐观态
                const key = clarificationKey(d?.runId, d?.clarificationId);
                if (key) answeredClarificationsRef.current.add(key);
                setTimeline((prev) =>
                  applyClarificationState(prev, d, 'ANSWERED', d?.response),
                );
              } else if (evtType === 'clarification_dismissed') {
                // 后端 ClarificationStatus 只有 PENDING/RESPONDED/DISMISSED，
                // 不存在 clarification_expired —— 该分支此前永远不会命中。
                setTimeline((prev) =>
                  applyClarificationState(prev, d, 'CANCELLED'),
                );
              }
              // 澄清统一走底部浮层；timeline 只保留审计行。
              void refreshPendingClarifications(sid);
              return;
            }
            if (evtType === 'content_token') {
              // 主 Agent 回复文本：由统一 Timeline 打字机在流式分支（seq+assistant）处理，
              // 此处不再维护独立主 timeline/回复气泡。
              return;
            }
          } catch {}
        },
        onError: (err: unknown) => {
          setSseConnected(false);
          // 诊断：打印断开原因（stream ended / connect timeout / HTTP 状态 / 底层 read 错误）
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
            setTimeout(() => {
              if (currentSessionIdRef.current === sid) connectSSE(sid);
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
            // chat 成功后立即拉一次最新 timeline（用户行 + assistant 容器行），
            // 避免依赖单靠 SSE/终态事件（空游标此前会被跳过）
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
            .catch(() => navigate('/chat', { replace: true }));
        }
        setSelectedModelRouteId(undefined);
        setHasMoreHistory(true);
        historyPageRef.current = 1;
        setMessages([]);
        reconnectCountRef.current = 0;
        runUserMessageRef.current = new Map();
        setTodos([]);
        setToolCalls([]);
        setSessionPanelOpen(true);
        // 统一 Timeline：重置并加载首尾一页（每页由后端 keyset 控制）
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
        connectSSE(sid);
      }
    }
    return () => {
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
        // 立即本地解除该澄清行（不等 SSE），并把发送态复位
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
      sending ||
      toolApprovalBatch ||
      clarificationBatch
    ) {
      return;
    }

    // 发送新消息前先取消 timeline 上仍 PENDING 的澄清（card 的唯一渲染源）
    const pendingRows = timeline.filter(
      (r: any) => r.kind === 'clarification' && r.state === 'PENDING',
    );
    for (const r of pendingRows) {
      agentApi.sessions
        .clarify(
          currentSession.id,
          r.runId,
          '__cancel__',
          r.content?.clarificationId,
        )
        .catch(() => {});
    }
    if (pendingRows.length > 0) cancelPendingClarificationsLocally();

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
      );
      if (res?.runId) {
        currentRunIdRef.current = res.runId;
        runUserMessageRef.current.set(res.runId, msg);
        // 乐观用户气泡此刻才拿到后端 runId：回填以便按 run 锚定排序（后端为准）
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
        // 统一 Timeline：拉最新页补用户行/助手容器
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
      const errMsg =
        e?.data?.userTip ||
        e?.data?.errorMessage ||
        e?.message ||
        'Failed to create session';
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
      <div className={styles.layout}>
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
            />
          </div>
          <div
            className={styles.sidebarToggle}
            onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
          >
            {sidebarCollapsed ? <RightOutlined /> : <LeftOutlined />}
          </div>
        </div>
        <div className={styles.main}>
          {currentSession ? (
            <ChatMain
              currentSession={currentSession}
              instances={instances}
              selectedModelRouteId={selectedModelRouteId}
              onModelRouteChange={setSelectedModelRouteId}
              modelRoutes={modelRoutes}
              sseConnected={sseConnected}
              messages={messages}
              hasMoreHistory={hasMoreHistory}
              onLoadMoreHistory={loadMoreHistory}
              inputValue={inputValue}
              onInputValueChange={setInputValue}
              sending={sending}
              onSendMessage={sendMessage}
              onCancelSend={() => {
                // 先取消 timeline 上仍 PENDING 的澄清（澄清卡的唯一渲染源），
                // 并立即本地解锁——避免 messages 残留 pending 导致「停止无反应」。
                const pendingRows = timeline.filter(
                  (r: any) =>
                    r.kind === 'clarification' && r.state === 'PENDING',
                );
                for (const r of pendingRows) {
                  if (currentSession?.id) {
                    agentApi.sessions
                      .clarify(
                        currentSession.id,
                        r.runId,
                        '__cancel__',
                        r.content?.clarificationId,
                      )
                      .catch(() => {});
                  }
                }
                cancelPendingClarificationsLocally();
                // sending 不乐观解锁：等该 run 终态事件恢复；8s 兜底防后端未发终态
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
                      // 不再静默吞错：会话归属校验失败(FORBIDDEN)等会让“停止”看起来毫无反应。
                      console.warn('session stop failed', e);
                      message.error(
                        e?.message ? `停止失败：${e.message}` : '停止失败，请重试',
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
              timelineHasMore={timelineHasMore}
              onLoadOlderTimeline={loadOlderTimeline}
              onRespondClarify={handleTimelineClarify}
              subAgentLive={subAgentLive}
              runMode={runMode}
              onRunModeChange={handleRunModeChange}
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
        <SessionPanel
          open={sessionPanelOpen}
          onClose={() => setSessionPanelOpen(false)}
          todos={todos}
          toolStats={toolStats}
          sessionUsage={sessionUsage}
          maxInputTokens={modelRoutes.find((r: any) => r.id === selectedModelRouteId)?.maxInputTokens}
        />
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
    </PageContainer>
  );
}

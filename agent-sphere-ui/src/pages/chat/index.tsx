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
import type { SubAgentLiveMap } from './components/chat/subAgentTypes';
import ExpandModal from './components/ExpandModal';
import InstanceDrawer from './components/InstanceDrawer';
import Landing from './components/Landing';
import SessionPanel from './components/SessionPanel';
import Sidebar from './components/Sidebar';
import { useStyles } from './style';

const SESSION_PAGE_SIZE = 10;

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
        const hasClarifications =
          !!r.clarifications && r.clarifications.length > 0;
        if (hasClarifications) {
          const clarifications = r.clarifications
            ? r.clarifications.map((c: any) => ({
                clarificationId: c.clarificationId,
                runId: c.runId,
                sessionId: c.sessionId,
                title: c.title,
                type: c.type,
                options: c.options
                  ? (() => {
                      try {
                        return JSON.parse(c.options);
                      } catch {
                        return [];
                      }
                    })()
                  : [],
                status: c.status,
                userResponse: c.userResponse,
              }))
            : [];
          historyMsgs.push({
            role: 'ai',
            content: r.assistantReply || '',
            runId: r.id,
            ts: toTs(r.createdAt),
            clarifications,
          });
        }
      }
      if (page === 1) {
        setMessages(historyMsgs);
      } else {
        setMessages((prev) => [...historyMsgs, ...prev]);
      }
    } catch {}
  }, []);

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
          m.set(r.seq, { ...(m.get(r.seq) || {}), ...r });
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
    try {
      // 空游标（新会话首条消息）→ 直接拉尾页；否则按 afterSeq 增量补行
      const page = await agentApi.sessions.getTimeline(
        sid,
        newestSeqRef.current != null
          ? { afterSeq: newestSeqRef.current, limit: 10 }
          : { limit: 10 },
      );
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

  useEffect(() => {
    if (currentSession?.id) void refreshSessionUsage(currentSession.id);
  }, [currentSession?.id]);

  const handleTimelineClarify = (row: any, response: string) => {
    if (!currentSession?.id || !row?.runId) return;
    agentApi.sessions
      .clarify(currentSession.id, row.runId, response, row.clarificationId)
      .catch(() => {});
    // 乐观回显：立即将澄清行置为已应答并显示用户提交内容。
    // 以 runId 为主匹配键（clarificationId 可能缺失/经 wrapReasoning 丢失），保证能命中。
    if (row.runId != null) {
      setTimeline((prev) =>
        prev.map((r) => {
          if (
            r.kind === 'clarification' &&
            Number(r.runId) === Number(row.runId) &&
            (row.clarificationId == null ||
              r.content?.clarificationId == null ||
              String(r.content.clarificationId) === String(row.clarificationId))
          ) {
            return {
              ...r,
              state: 'ANSWERED',
              content: { ...r.content, response },
            };
          }
          return r;
        }),
      );
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
          const nl = delta.indexOf('\n');
          const body = nl >= 0 ? delta.slice(nl + 1) : delta;
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

  const connectSSE = useCallback((sid: number) => {
    if (abortRef.current) abortRef.current.abort();
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
          setSseConnected(true);
          reconnectCountRef.current = 0;
          console.log('[SSE] connected');
        },
        onMessage: (payload) => {
          try {
            const parsed = JSON.parse(payload);
            const evtType = parsed.eventType || parsed.type || '';
            const d = parsed.data || parsed;

            // 会话守卫：忽略非当前会话的事件，防止旧会话仍在流式时污染当前 messages/timeline（串台/重复）
            if (currentSessionIdRef.current !== sid) return;

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
                      seq: -1,
                      kind: 'subagent',
                      refSubAgentRunId: liveSubId,
                      status: 'RUNNING',
                      title: d?.displayNameCn || d?.displayName || '子 Agent',
                      content: {
                        displayName: d?.displayNameCn || d?.displayName,
                        state: 'RUNNING',
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
                'tool_call_succeeded',
                'tool_call_failed',
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
              if (evtType === 'clarification_pending' && d?.runId) {
                const opts = d.argumentsJson
                  ? (() => {
                      try {
                        return JSON.parse(d.argumentsJson);
                      } catch {
                        return [];
                      }
                    })()
                  : [];
                const clarificationObj = {
                  clarificationId: d.clarificationId,
                  runId: d.runId,
                  sessionId: d.sessionId,
                  title: d.prompt || '',
                  type: d.type || 'confirm',
                  options: opts,
                  status: 'pending' as const,
                };
                setMessages((prev) => {
                  const idx = prev.findIndex(
                    (m: any) => m.role === 'ai' && m.runId === d.runId,
                  );
                  if (idx >= 0) {
                    return prev.map((m, i) =>
                      i === idx
                        ? {
                            ...m,
                            clarifications: [
                              ...((m as any).clarifications || []).filter(
                                (c: any) =>
                                  c.clarificationId !== d.clarificationId,
                              ),
                              clarificationObj,
                            ],
                            _pending: false,
                          }
                        : m,
                    );
                  }
                  return [
                    ...prev,
                    {
                      role: 'ai',
                      content: '',
                      runId: d.runId,
                      ts: Date.now(),
                      clarifications: [clarificationObj],
                      _pending: false,
                    },
                  ];
                });
              }
              if (evtType === 'clarification_responded') {
                setMessages((prev) =>
                  prev.map((m) =>
                    (m as any).runId === d?.runId
                      ? {
                          ...m,
                          clarifications: ((m as any).clarifications || []).map(
                            (c: any) =>
                              c.clarificationId === d.clarificationId
                                ? {
                                    ...c,
                                    status: 'responded',
                                    userResponse: d.response,
                                  }
                                : c,
                          ),
                        }
                      : m,
                  ),
                );
                // 兜底：Timeline 澄清行同步置为已应答并回显用户提交内容（按 runId 匹配，clarificationId 缺失时仍命中）
                setTimeline((prev) =>
                  prev.map((r) => {
                    if (
                      r.kind === 'clarification' &&
                      Number(r.runId) === Number(d?.runId) &&
                      (d?.clarificationId == null ||
                        r.content?.clarificationId == null ||
                        String(r.content.clarificationId) ===
                          String(d.clarificationId))
                    ) {
                      return {
                        ...r,
                        state: 'ANSWERED',
                        content: {
                          ...r.content,
                          response: d?.response ?? r.content.response,
                        },
                      };
                    }
                    return r;
                  }),
                );
              }
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
        setSending(false);
      }
    },
    [currentSession],
  );

  const sendMessage = async (attachmentKeys?: string[]) => {
    const hasAttachment =
      Array.isArray(attachmentKeys) && attachmentKeys.length > 0;
    if ((!inputValue.trim() && !hasAttachment) || !currentSession || sending) {
      return;
    }

    // Auto-cancel any pending clarifications before sending a new message
    for (const m of messages) {
      const clarifications = (m as any).clarifications;
      if (clarifications) {
        for (const c of clarifications) {
          if (c.status === 'pending') {
            agentApi.sessions
              .clarify(c.sessionId, c.runId, '__cancel__', c.clarificationId)
              .catch(() => {});
          }
        }
      }
    }

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
                // 不乐观解锁：保持 sending=true（按钮维持停止态），等待该 run 的终态事件再恢复；
                // 8s 兜底强制恢复，避免后端未发布终态事件时卡死
                if (stopFallbackRef.current) {
                  clearTimeout(stopFallbackRef.current);
                }
                stopFallbackRef.current = window.setTimeout(() => {
                  stopFallbackRef.current = null;
                  setSending(false);
                }, 8000);
                // Cancel any pending clarifications
                for (const m of messages) {
                  const clarifications = (m as any).clarifications;
                  if (clarifications) {
                    for (const c of clarifications) {
                      if (c.status === 'pending') {
                        agentApi.sessions
                          .clarify(
                            c.sessionId,
                            c.runId,
                            '__cancel__',
                            c.clarificationId,
                          )
                          .catch(() => {});
                      }
                    }
                  }
                }
                if (currentSession?.id) {
                  agentApi.runs
                    .sessionStop(currentSession.id)
                    .catch(() => console.warn('session stop failed'));
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
              sessionUsage={sessionUsage}
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
          toolCalls={toolCalls}
          messages={messages}
          runUserMessages={runUserMessageRef.current}
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

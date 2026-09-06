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

/** 子 Agent live 唯一身份：后端对同一 skill 执行全程下发同一 subAgentRunId，
 *  统一用它做 key（缺失才回退 nodeName 的 skill: 后缀）；两者皆无 → 非真实子 Agent 事件，返回 null。 */
function subAgentIdentity(runId: any, subRunId: any, nodeName: any) {
  const rid = runId != null ? String(runId) : '';
  if (subRunId != null) {
    return { key: `s-${rid}-${subRunId}`, skillId: String(subRunId) };
  }
  const n = String(nodeName || '');
  if (n.startsWith('skill:')) {
    const sid = n.slice('skill:'.length);
    return { key: `s-${rid}-${sid}`, skillId: sid };
  }
  return null;
}

/** 把 run 的 activities（llm_interaction + tool_call，倒序）归组成 interaction 级 timeline 条目。
 *  runId 为所属回合（后端单调），供 MessageList 按 run 锚定排序（后端为准，避免跨回合墙钟比较错序）。 */
function buildTimelineFromActivities(
  activities: any[],
  dir: 'asc' | 'desc' = 'asc',
  runId?: number,
): any[] {
  if (!Array.isArray(activities) || activities.length === 0) return [];
  const list = [...activities];
  if (dir === 'desc') list.reverse(); // 转正序；接口返回 created_at DESC
  const entries: any[] = [];
  for (const act of list) {
    if (!act) continue;
    if (act.activityType === 'llm_interaction') {
      entries.push({
        key: `${act.id}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        activityId: act.id,
        runId: runId ?? null,
        seq: entries.length,
        reason: act.reasoning || '',
        reply: act.replyContent || act.reply || '',
        tools: [],
        status: act.success === false ? 'FAILED' : 'COMPLETED',
        createdAt: act.createdAt,
      });
    } else if (act.activityType === 'tool_call') {
      const cur = entries[entries.length - 1];
      const tool = {
        callId: `h-${act.stepId ?? act.id}`,
        name: act.displayNameCn || act.toolName || 'tool',
        args: act.argumentsJson || '',
        artifact: act.artifact || '',
        status: act.toolStatus || 'PENDING',
      };
      if (cur) {
        cur.tools = [...cur.tools, tool];
      } else {
        entries.push({
          key: `h${act.stepId ?? act.id}-${Date.now()}`,
          activityId: act.stepId,
          runId: runId ?? null,
          seq: entries.length,
          reason: '',
          reply: '',
          tools: [tool],
          status: 'COMPLETED',
          createdAt: act.createdAt,
        });
      }
    }
  }
  return entries;
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
  const [inputValue, setInputValue] = useState('');
  const [sending, setSending] = useState(false);
  const [hasMoreHistory, setHasMoreHistory] = useState(true);
  const [sseConnected, setSseConnected] = useState(false);
  const [instances, setInstances] = useState<any[]>([]);
  const [chosenInstance, setChosenInstance] = useState('');
  const [currentInstanceObj, setCurrentInstanceObj] = useState<any>(null);
  const [modelRoutes, setModelRoutes] = useState<any[]>([]);
  const [selectedModelRouteId, setSelectedModelRouteId] = useState<
    number | undefined
  >(undefined);
  const [collapsedKeys, setCollapsedKeys] = useState<Set<string>>(new Set());
  const [expandOpen, setExpandOpen] = useState(false);
  const [expandText, setExpandText] = useState('');
  const [instanceDrawerOpen, setInstanceDrawerOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [configModelInstance, setConfigModelInstance] = useState<any>(null);
  const [todos, setTodos] = useState<any[]>([]);
  const [toolCalls, setToolCalls] = useState<any[]>([]);
  const [sessionPanelOpen, setSessionPanelOpen] = useState(false);
  // 子 Agent 实时聚合（SSE）与历史运行
  const [subAgentLive, setSubAgentLive] = useState<any[]>([]);
  const [subAgentHistorical, setSubAgentHistorical] = useState<any[]>([]);
  // 主 Agent live interaction 级 timeline：每个 interaction 一组成 [reason/reply/tool]
  const [mainTimeline, setMainTimeline] = useState<any[]>([]);
  // 主 Agent 历史 interaction 级 timeline（loadHistory 组装）
  const [historyTimeline, setHistoryTimeline] = useState<any[]>([]);
  const mainTimelineSeqRef = useRef<Record<number, number>>({});

  const historyPageRef = useRef(1);
  const abortRef = useRef<AbortController | null>(null);
  const currentSessionIdRef = useRef<number | null>(null);
  const reconnectCountRef = useRef(0);
  const seenReplyRunIdsRef = useRef<Set<number>>(new Set());
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

      // 并行：reasoning 补拉 + 子 Agent 历史（仅首屏） + 各 run activities
      const [timelineEntries] = await Promise.all([
        (async () => {
          const entries: any[] = [];
          await Promise.all(
            runs
              .filter(
                (r: any) =>
                  !(r.assistantReply && seenReplyRunIdsRef.current.has(r.id)),
              )
              .map(async (r: any) => {
                try {
                  const activitiesRes = await agentApi.activities.listByRun(
                    r.id,
                    sid,
                    0,
                    100,
                  );
                  if (activitiesRes && Array.isArray(activitiesRes.records)) {
                    entries.push(
                      ...buildTimelineFromActivities(
                        activitiesRes.records,
                        'desc',
                        r.id,
                      ),
                    );
                  }
                } catch {
                  // activities 拉取失败：降级为无 timeline
                }
              }),
          );
          return entries;
        })(),
        (async () => {
          if (page !== 1) return;
          try {
            const subRuns = await agentApi.sessions.getSubAgentRuns(sid);
            if (Array.isArray(subRuns)) setSubAgentHistorical(subRuns);
          } catch {
            // 子 Agent 历史拉取失败不影响主消息
          }
        })(),
      ]);

      const historyMsgs: any[] = [];
      const historyKeys: string[] = [];
      for (const r of runs) {
        if (r.assistantReply && seenReplyRunIdsRef.current.has(r.id)) continue;
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
          historyKeys.push(`run-${r.id}`);
        }
      }
      if (timelineEntries.length > 0) {
        setHistoryTimeline((prev) => [...timelineEntries, ...prev]);
      }
      setCollapsedKeys((prev) => {
        const next = new Set(prev);
        for (const k of historyKeys) next.add(k);
        return next;
      });
      if (page === 1) {
        setMessages(historyMsgs);
      } else {
        setMessages((prev) => [...historyMsgs, ...prev]);
      }
    } catch {}
  }, []);

  const connectSSE = useCallback((sid: number) => {
    if (abortRef.current) abortRef.current.abort();
    currentSessionIdRef.current = sid;
    setSseConnected(false);
    reconnectCountRef.current = 0;

    const controller = new AbortController();
    abortRef.current = controller;
    const token = getToken();
    if (!token) return;

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

            // 始终跟踪当前 runId（含任务系统发起的 run），保证停止能命中正确 run
            const msgRunId = Number(d?.runId);
            if (Number.isFinite(msgRunId)) {
              currentRunIdRef.current = msgRunId;
            }

            if (evtType === 'reasoning_token') {
              const raw = d?.response || '';
              const subType = d?.reasoningSubType || '';
              const publishId = d?.publishId;
              const runId = d?.runId;

              // 子 Agent（nodeName=skill: 或 subAgentRunId）→ 路由进子 Agent 实时卡片
              const subRunId = d?.subAgentRunId;
              const isSubAgent =
                subRunId != null ||
                String(d?.nodeName || '').startsWith('skill:');
              if (isSubAgent) {
                const identity = subAgentIdentity(runId, subRunId, d?.nodeName);
                if (!identity) return;
                const skillId = identity.skillId;
                if (
                  raw &&
                  subType !== 'tool_call_started' &&
                  subType !== 'tool_call_in_progress' &&
                  subType !== 'tool_call_succeeded' &&
                  subType !== 'tool_call_failed'
                ) {
                  const skillName = raw.startsWith('▶')
                    ? raw.replace('▶ Skill', '⚙️ Skill').split('\n')[0]
                    : '';
                  setSubAgentLive((prev) => {
                    const key = identity.key;
                    const exists = prev.find((s) => s.key === key);
                    if (exists) {
                      return prev.map((s) =>
                        s.key === key
                          ? {
                              ...s,
                              reasoning: s.reasoning + raw,
                              // 推理首帧解析出的 marker 名最完整，覆盖此前的内容/工具事件回退名
                              name: skillName ? skillName : s.name,
                            }
                          : s,
                      );
                    }
                    return [
                      ...prev,
                      {
                        key,
                        runId: runId ?? null,
                        skillId,
                        subAgentRunId: subRunId ?? null,
                        name: skillName ? skillName : `Skill ${skillId}`,
                        status: 'RUNNING',
                        reasoning: raw,
                        reply: '',
                        toolCalls: [],
                      },
                    ];
                  });
                  return; // 子 Agent reasoning 不进主推理气泡
                }
              }

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
                // 子 Agent tool call → 路由进子 Agent 卡片
                const subRunId = d?.subAgentRunId;
                const isSubTool =
                  subRunId != null ||
                  String(d?.publishId || '').startsWith('skill-') ||
                  String(d?.nodeName || '').startsWith('skill:');
                if (isSubTool) {
                  const rid = d?.runId;
                  // 统一 identity：key 由 subAgentRunId（或 skill: 后缀）唯一决定，避免同一 skill 拆成多个 tab
                  const identity = subAgentIdentity(rid, subRunId, d?.nodeName);
                  if (!identity) return;
                  const skillId = identity.skillId;
                  const key = identity.key;
                  setSubAgentLive((prev) => {
                    const exists = prev.find((s) => s.key === key);
                    const tool = {
                      callId: publishId || `${key}-${Date.now()}`,
                      toolName: d?.toolName || name || 'tool',
                      args: d?.argumentsJson || '',
                      status,
                    };
                    if (!exists) {
                      return [
                        ...prev,
                        {
                          key,
                          runId: rid ?? null,
                          skillId,
                          subAgentRunId: subRunId ?? null,
                          name: `Skill ${skillId}`,
                          status: 'RUNNING',
                          reasoning: '',
                          reply: '',
                          toolCalls: [tool],
                        },
                      ];
                    }
                    return prev.map((s) => {
                      if (s.key !== key) return s;
                      const idx = s.toolCalls.findIndex(
                        (t: any) => t.callId === tool.callId,
                      );
                      if (idx >= 0) {
                        const next = [...s.toolCalls];
                        next[idx] = { ...next[idx], ...tool };
                        return { ...s, toolCalls: next };
                      }
                      return { ...s, toolCalls: [...s.toolCalls, tool] };
                    });
                  });
                  return;
                }
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
                // 子 Agent tool call → 路由进子 Agent 卡片
                const subRunId = d?.subAgentRunId;
                const isSubTool =
                  subRunId != null ||
                  String(d?.publishId || '').startsWith('skill-') ||
                  String(d?.nodeName || '').startsWith('skill:');
                if (isSubTool) {
                  const rid = d?.runId;
                  // 统一 identity：skillId 仅用于显示，key 由 subAgentRunId（或 skill: 后缀）唯一决定
                  const identity = subAgentIdentity(rid, subRunId, d?.nodeName);
                  if (!identity) return;
                  const skillId = identity.skillId;
                  const key = identity.key;
                  setSubAgentLive((prev) => {
                    const exists = prev.find((s) => s.key === key);
                    if (exists) {
                      return prev.map((s) =>
                        s.key === key
                          ? {
                              ...s,
                              toolCalls: [
                                ...s.toolCalls,
                                {
                                  callId: publishId || `${key}-${Date.now()}`,
                                  toolName: d?.toolName || name || 'tool',
                                  args: d?.argumentsJson || '',
                                  status: 'started',
                                },
                              ],
                            }
                          : s,
                      );
                    }
                    return [
                      ...prev,
                      {
                        key,
                        runId: rid ?? null,
                        skillId,
                        subAgentRunId: subRunId ?? null,
                        name: `Skill ${skillId}`,
                        status: 'RUNNING',
                        reasoning: '',
                        reply: '',
                        toolCalls: [
                          {
                            callId: publishId || `${key}-${Date.now()}`,
                            toolName: d?.toolName || name || 'tool',
                            args: d?.argumentsJson || '',
                            status: 'started',
                          },
                        ],
                      },
                    ];
                  });
                  return;
                }
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
                // 主 Agent tool started → 同步进 interaction timeline 的 tools
                if (runId) {
                  const rid = Number(runId);
                  setMainTimeline((prev) => {
                    const cur = mainTimelineSeqRef.current[rid] ?? 0;
                    const hasCur = prev.some(
                      (it) => Number(it.runId) === rid && it.seq === cur,
                    );
                    let seq = cur;
                    let base: any[] = prev;
                    if (!hasCur) {
                      seq = 0;
                      base = [
                        ...prev,
                        {
                          key: `m-${rid}-0`,
                          runId: rid,
                          seq: 0,
                          reason: '',
                          reply: '',
                          tools: [],
                          status: 'RUNNING',
                          ts: Date.now(),
                        },
                      ];
                      mainTimelineSeqRef.current[rid] = 0;
                    }
                    const key = `m-${rid}-${seq}`;
                    return base.map((it) =>
                      it.key === key
                        ? {
                            ...it,
                            tools: [
                              ...it.tools,
                              {
                                callId: publishId || `${key}-${Date.now()}`,
                                name: name || d?.toolName || 'tool',
                                args: d?.argumentsJson || '',
                                status: 'started',
                              },
                            ],
                          }
                        : it,
                    );
                  });
                }
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
                // 该 run 已在 live 展示 → 后续 loadHistory 不再重复重建它的回复（修复“最新一条重复展示”）
                if (runId != null)
                  seenReplyRunIdsRef.current.add(Number(runId));
                // 主 Agent run 终态：同 run 的子 Agent activity 标记为对应状态
                if (runId) {
                  const finalStatus =
                    subType === 'run_completed'
                      ? 'COMPLETED'
                      : subType === 'run_cancelled'
                        ? 'CANCELLED'
                        : 'FAILED';
                  setSubAgentLive((prev) =>
                    prev.map((s) =>
                      Number(s.runId) === Number(runId)
                        ? { ...s, status: finalStatus }
                        : s,
                    ),
                  );
                  // 主 Agent timeline 同样收口
                  setMainTimeline((prev) =>
                    prev.map((it) =>
                      Number(it.runId) === Number(runId)
                        ? { ...it, status: finalStatus }
                        : it,
                    ),
                  );
                }

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

              // 主 Agent 模型推理（llm）→ interaction 级 timeline（非子、非系统状态行）
              if (
                d?.reasoningType === 'llm' &&
                subType === 'model_reason' &&
                runId &&
                raw
              ) {
                const rid = Number(runId);
                setMainTimeline((prev) => {
                  const cur = mainTimelineSeqRef.current[rid] ?? 0;
                  // 当前 seq 已有 tool 或不存在 → 新开 interaction
                  const hasCur = prev.some(
                    (it) => Number(it.runId) === rid && it.seq === cur,
                  );
                  const curHasTool =
                    hasCur &&
                    prev
                      .filter(
                        (it) => Number(it.runId) === rid && it.seq === cur,
                      )
                      .some((it) => it.tools?.length > 0);
                  let seq = cur;
                  if (!hasCur || curHasTool) {
                    seq = cur + 1;
                    mainTimelineSeqRef.current[rid] = seq;
                  }
                  const key = `m-${rid}-${seq}`;
                  const existing = prev.find((it) => it.key === key);
                  if (existing) {
                    // 追加推理内容，但保持条目的原始时间戳（ts 不随 delta 刷新，
                    // 避免模型输出中被置于用户下一条消息之后的排序错位）
                    return prev.map((it) =>
                      it.key === key ? { ...it, reason: it.reason + raw } : it,
                    );
                  }
                  return [
                    ...prev,
                    {
                      key,
                      runId: rid,
                      seq,
                      reason: raw,
                      reply: '',
                      tools: [] as any[],
                      status: 'RUNNING',
                      ts: Date.now(),
                    },
                  ];
                });
                return; // 主推理进 timeline，不再进单个 reasoning 气泡
              }

              // 推理已改为 interaction timeline 展示，不再 push 独立 reasoning 气泡；
              // 剩余的 isSystem 状态行（run_pending 等）忽略；intent_retry 时清理空 ai 消息。
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
              }
              return;
            }
            if (evtType === 'content_token') {
              const rid = d?.runId;
              const delta = d?.response || '';
              // 子 Agent reply → 路由进子 Agent 卡片（不进主 reply 气泡）
              const subRunId = d?.subAgentRunId;
              const isSubReply =
                subRunId != null ||
                String(d?.nodeName || '').startsWith('skill:');
              if (isSubReply) {
                const identity = subAgentIdentity(rid, subRunId, d?.nodeName);
                if (!identity) return;
                const skillId = identity.skillId;
                if (rid && delta) {
                  setSubAgentLive((prev) => {
                    const key = identity.key;
                    const exists = prev.find((s) => s.key === key);
                    if (exists) {
                      return prev.map((s) =>
                        s.key === key ? { ...s, reply: s.reply + delta } : s,
                      );
                    }
                    return [
                      ...prev,
                      {
                        key,
                        runId: rid,
                        skillId,
                        subAgentRunId: subRunId ?? null,
                        name: `Skill ${skillId}`,
                        status: 'RUNNING',
                        reasoning: '',
                        reply: delta,
                        toolCalls: [],
                      },
                    ];
                  });
                }
                return;
              }
              // 主 Agent reply → interaction 级 timeline（不进单个 ai 气泡，防止重复）
              if (rid && delta) {
                const ridN = Number(rid);
                setMainTimeline((prev) => {
                  const cur = mainTimelineSeqRef.current[ridN] ?? 0;
                  const hasCur = prev.some(
                    (it) => Number(it.runId) === ridN && it.seq === cur,
                  );
                  let seq = cur;
                  let base: any[] = prev;
                  if (!hasCur) {
                    seq = 0;
                    base = [
                      ...prev,
                      {
                        key: `m-${ridN}-0`,
                        runId: ridN,
                        seq: 0,
                        reason: '',
                        reply: '',
                        tools: [],
                        status: 'RUNNING',
                        ts: Date.now(),
                      },
                    ];
                    mainTimelineSeqRef.current[ridN] = 0;
                  }
                  const key = `m-${ridN}-${seq}`;
                  return base.map((it) =>
                    it.key === key ? { ...it, reply: it.reply + delta } : it,
                  );
                });
                return;
              }
              return;
            }
          } catch {}
        },
        onError: () => {
          setSseConnected(false);
          if (currentSessionIdRef.current === sid) {
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
        setCollapsedKeys(new Set());
        seenReplyRunIdsRef.current = new Set();
        reconnectCountRef.current = 0;
        runUserMessageRef.current = new Map();
        setTodos([]);
        setToolCalls([]);
        // 切换会话时清空 interaction timeline，避免跨会话残留旧数据
        setHistoryTimeline([]);
        setMainTimeline([]);
        mainTimelineSeqRef.current = {};
        setSessionPanelOpen(true);
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

  const sendMessage = async () => {
    if (!inputValue.trim() || !currentSession || sending) return;

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
              collapsedKeys={collapsedKeys}
              onCollapsedKeysChange={setCollapsedKeys}
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
              subAgentLive={subAgentLive}
              subAgentHistorical={subAgentHistorical}
              mainTimeline={mainTimeline}
              historyTimeline={historyTimeline}
              onLoadSubAgentTimeline={async (id: number) => {
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

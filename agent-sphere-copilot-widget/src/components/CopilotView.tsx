import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { UIEvent } from 'react';
import { HttpAgent, type AbstractAgent } from '@ag-ui/client';
import { CopilotChat, CopilotKit } from '@copilotkit/react-core/v2';
import { ApiError, createApi, stopSession } from '../api';
import type { WidgetConfig } from '../config';
import type {
  ClarificationVO,
  InstanceVO,
  RunVO,
  SessionTodoVO,
  SessionVO,
  UserVO,
} from '../types';
import { ChatAuxPanel } from './ChatAuxPanel';
import { connectReasoningStream } from '../reasoningStream';

const HISTORY_PAGE_SIZE = 3;
const AGENT_PAGE_SIZE = 5;
const SESSION_PAGE_SIZE = 5;
const SCROLL_TOP_THRESHOLD = 40;
const SCROLL_END_THRESHOLD = 20;
const ACTIVE_SESSION_KEY = 'agent-sphere-widget:active-session';

interface CopilotViewProps {
  config: WidgetConfig;
  user: UserVO;
}

type ChatMessage = Parameters<AbstractAgent['setMessages']>[0][number];

interface ClarificationState {
  clarificationId: string;
  title: string;
  type: string;
  options: { label: string; value: string }[];
  sessionId?: number;
  runId?: number;
}

function normalizeOptions(raw: unknown): { label: string; value: string }[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  const options: { label: string; value: string }[] = [];
  for (const item of raw) {
    if (typeof item === 'string' && item.trim()) {
      options.push({ label: item, value: item });
    } else if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>;
      const label = typeof o.label === 'string' ? o.label.trim() : '';
      const value = typeof o.value === 'string' ? o.value.trim() : '';
      if (!label && !value) {
        continue;
      }
      options.push({ label: label || value, value: value || label });
    }
  }
  return options;
}

const CLARIFICATION_RESUME_PREFIX =
  '[User Response to Clarification — execute the next step now, do NOT ask another question]: ';

const CLARIFICATION_DISMISSED_RESPONSES = ['__dismissed__', '__cancel__'];

function stripClarificationPrefix(text: string): string {
  return text.startsWith(CLARIFICATION_RESUME_PREFIX)
    ? text.slice(CLARIFICATION_RESUME_PREFIX.length)
    : text;
}

function parseClarificationOptions(raw: string | null | undefined): unknown {
  if (!raw) {
    return [];
  }
  try {
    return JSON.parse(raw);
  } catch {
    return [];
  }
}

function clarificationResolvedLabel(c: ClarificationVO): string {
  const response = c.userResponse;
  if (!response) {
    return '';
  }
  if (c.type === 'choice') {
    const options = normalizeOptions(parseClarificationOptions(c.options));
    const hit = options.find((o) => o.value === response || o.label === response);
    return hit ? hit.label : response;
  }
  if (c.type === 'confirm') {
    return '已确认';
  }
  return response;
}

function buildClarificationBlock(clarifications: ClarificationVO[]): string {
  const lines: string[] = [];
  for (const c of clarifications) {
    const title = c.title || '澄清';
    if (CLARIFICATION_DISMISSED_RESPONSES.includes(c.userResponse)) {
      lines.push(`> ❌ **${title}**\n>\n> 已取消`);
    } else if (c.userResponse) {
      lines.push(`> ✅ **${title}**\n>\n> 已回复：${clarificationResolvedLabel(c)}`);
    } else {
      lines.push(`> 🤔 **${title}**\n>\n> 等待回复`);
    }
  }
  return lines.join('\n\n');
}

/** 实时澄清应答/取消时，把澄清问题+结果作为一条 assistant 消息注入当前聊天（与历史渲染同格式） */
function buildLiveClarificationMessage(
  c: ClarificationState,
  response: string,
): ChatMessage {
  const vo: ClarificationVO = {
    clarificationId: c.clarificationId,
    runId: c.runId ?? 0,
    sessionId: c.sessionId ?? 0,
    messageId: 0,
    title: c.title,
    type: c.type,
    options: JSON.stringify(c.options),
    userResponse: response,
    status: 'responded',
  };
  return {
    id: `a-clarify-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    role: 'assistant',
    content: buildClarificationBlock([vo]),
  };
}

function toChatMessages(runs: RunVO[]): ChatMessage[] {
  const messages: ChatMessage[] = [];
  for (const r of runs) {
    if (
      !r.clarificationResponse &&
      r.userMessage &&
      r.userMessage !== '{}' &&
      r.userMessage.trim()
    ) {
      messages.push({
        id: `u-${r.id}`,
        role: 'user',
        content: stripClarificationPrefix(r.userMessage),
      });
    }
    const block = r.clarifications?.length
      ? buildClarificationBlock(r.clarifications)
      : '';
    if (r.reasoning && r.reasoning.trim()) {
      messages.push({
        id: `r-${r.id}`,
        role: 'reasoning',
        content: r.reasoning,
      });
    }
    if (r.assistantReply && r.assistantReply !== '{}' && r.assistantReply.trim()) {
      messages.push({
        id: `a-${r.id}`,
        role: 'assistant',
        content: block ? `${r.assistantReply}\n\n${block}` : r.assistantReply,
      });
    } else if (block) {
      messages.push({ id: `a-${r.id}`, role: 'assistant', content: block });
    }
  }
  return messages;
}

function mergeById<T extends { id: number }>(prev: T[], next: T[]): T[] {
  const map = new Map<number, T>();
  for (const item of prev) map.set(item.id, item);
  for (const item of next) map.set(item.id, item);
  return Array.from(map.values());
}

export function CopilotView({ config, user }: CopilotViewProps) {
  const api = useMemo(
    () => createApi(config),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [config.apiBase, config.provider],
  );
  const apiBase = config.apiBase ?? '/api/v1';

  // 实例（分页）
  const [instances, setInstances] = useState<InstanceVO[]>([]);
  const [agentPage, setAgentPage] = useState(1);
  const [hasMoreAgents, setHasMoreAgents] = useState(false);
  const [loadingAgents, setLoadingAgents] = useState(false);
  // 会话（分页）
  const [sessions, setSessions] = useState<SessionVO[]>([]);
  const [sessionOffset, setSessionOffset] = useState(0);
  const [hasMoreSessions, setHasMoreSessions] = useState(false);
  const [loadingSessions, setLoadingSessions] = useState(false);
  // 选中
  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // 会话重命名
  const [editingSessionId, setEditingSessionId] = useState<number | null>(null);
  const [editingTitle, setEditingTitle] = useState('');
  // 归档二次确认
  const [confirmingArchiveId, setConfirmingArchiveId] = useState<number | null>(null);

  useEffect(() => {
    if (error === null) {
      return;
    }
    const timer = window.setTimeout(() => setError(null), 3000);
    return () => window.clearTimeout(timer);
  }, [error]);
  // 历史 / todos
  const historyPageRef = useRef(2);
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [todos, setTodos] = useState<SessionTodoVO[]>([]);
  // 澄清（ask_clarification interrupt）
  const [clarification, setClarification] = useState<ClarificationState | null>(null);
  const [clarificationText, setClarificationText] = useState('');

  // widget 自己发起的 run（AG-UI RUN_STARTED 的后端 runId）——被动流里跳过，避免与
  // CopilotKit 自带渲染重复。
  const ownRunIdsRef = useRef<Set<string>>(new Set());
  // 当前正在流式展示推理的任务 run：期间置 agent.isRunning=true，让 CopilotChat 以
  // "Thinking…" 展开流式渲染（否则任务 run 的推理块默认折叠成一行小字，看不见内容）。
  const streamingRunIdRef = useRef<string | null>(null);

  const agents = useMemo(() => {
    const record: Record<string, AbstractAgent> = {};
    for (const inst of instances) {
      const agent = new HttpAgent({
        url: `${apiBase}/copilot/agent/${inst.id}/services/chat/run`,
        headers: { Authorization: `Bearer ${user.token}` },
      });
      agent.subscribe({
        onCustomEvent: ({ event }) => {
          if (event.name === 'session_title_updated') {
            const sessionId = Number(event.value?.sessionId);
            const title = event.value?.title;
            if (Number.isFinite(sessionId) && typeof title === 'string') {
              setSessions((prev) =>
                prev.map((s) => (s.id === sessionId ? { ...s, title } : s)),
              );
            }
          }
        },
        onRunStartedEvent: ({ event }) => {
          const runId = (event as { runId?: unknown })?.runId;
          if (runId != null) {
            ownRunIdsRef.current.add(String(runId));
          }
        },
        onRunFinishedEvent: ({ event }) => {
          const outcome = event.outcome;
          if (
            outcome?.type === 'interrupt' &&
            Array.isArray(outcome.interrupts) &&
            outcome.interrupts.length > 0
          ) {
            const int = outcome.interrupts[0];
            const metadata = (int.metadata ?? {}) as Record<string, unknown>;
            setClarification({
              clarificationId: int.id,
              title: int.reason ?? '',
              type: (metadata.type as string) ?? 'confirm',
              options: normalizeOptions(metadata.options),
              sessionId:
                metadata.sessionId != null ? Number(metadata.sessionId) : undefined,
              runId: metadata.runId != null ? Number(metadata.runId) : undefined,
            });
          }
        },
      });
      const originalRun = agent.runAgent.bind(agent);
      agent.runAgent = ((input: unknown, ...rest: unknown[]) => {
        if (agent.isRunning) {
          return Promise.reject(new Error('上一个请求还在处理中，请稍候再试'));
        }
        return originalRun(input as never, ...(rest as never[]));
      }) as typeof agent.runAgent;
      record[String(inst.id)] = agent;
    }
    return record;
  }, [instances, apiBase, user.token]);

  const agentsRef = useRef(agents);
  agentsRef.current = agents;

  const agentListRef = useRef<HTMLDivElement>(null);
  const sessionListRef = useRef<HTMLDivElement>(null);

  const loadAgents = useCallback(
    async (page: number) => {
      setLoadingAgents(true);
      try {
        const res = await api.listInstancesPage(page, AGENT_PAGE_SIZE);
        const active = (res.records ?? []).filter((i) => i.status === 'ENABLED');
        setInstances((prev) => mergeById(prev, active));
        setHasMoreAgents(res.current < res.pages);
        setAgentPage(page + 1);
        setSelectedAgentId((prev) => prev ?? active[0]?.id ?? null);
      } catch (err) {
        setError((err as ApiError).message);
      } finally {
        setLoadingAgents(false);
        setLoading(false);
      }
    },
    [api],
  );

  useEffect(() => {
    void loadAgents(1);
  }, [api, loadAgents]);

  // 列表内容不足一屏（无滚动条）时自动补下一页，直到可滚动或加载完
  useEffect(() => {
    if (!hasMoreAgents || loadingAgents) {
      return;
    }
    const el = agentListRef.current;
    if (!el) {
      return;
    }
    const raf = requestAnimationFrame(() => {
      if (el.scrollHeight <= el.clientHeight + 1) {
        void loadAgents(agentPage);
      }
    });
    return () => cancelAnimationFrame(raf);
  }, [instances, hasMoreAgents, loadingAgents, agentPage, loadAgents]);

  const loadMoreSessions = useCallback(
    async (offset: number) => {
      if (selectedAgentId === null) {
        return;
      }
      setLoadingSessions(true);
      try {
        const list = await api.listSessions(offset, SESSION_PAGE_SIZE);
        setSessions((prev) => mergeById(prev, list));
        setHasMoreSessions(list.length >= SESSION_PAGE_SIZE);
        setSessionOffset(offset + list.length);
        setSelectedSessionId((prev) => {
          if (prev) {
            return prev;
          }
          const first = list.find((s) => s.agentInstanceId === selectedAgentId);
          return first ? first.id : prev;
        });
      } catch (err) {
        setError((err as ApiError).message);
      } finally {
        setLoadingSessions(false);
      }
    },
    [api, selectedAgentId],
  );

  useEffect(() => {
    if (selectedAgentId === null) {
      return;
    }
    setSessions([]);
    setSessionOffset(0);
    setHasMoreSessions(true);
    setSelectedSessionId(null);
    void loadMoreSessions(0);
  }, [selectedAgentId, loadMoreSessions]);

  // 会话列表不足一屏时自动补下一页
  useEffect(() => {
    if (!hasMoreSessions || loadingSessions) {
      return;
    }
    const el = sessionListRef.current;
    if (!el) {
      return;
    }
    const raf = requestAnimationFrame(() => {
      if (el.scrollHeight <= el.clientHeight + 1) {
        void loadMoreSessions(sessionOffset);
      }
    });
    return () => cancelAnimationFrame(raf);
  }, [sessions, hasMoreSessions, loadingSessions, sessionOffset, loadMoreSessions]);

  const handleCreateSession = async () => {
    if (selectedAgentId === null) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const created = await api.createSession(selectedAgentId, '新的会话');
      setSessions((prev) => [created, ...prev.filter((s) => s.id !== created.id)]);
      setSelectedSessionId(created.id);
    } catch (err) {
      setError((err as ApiError).message);
    } finally {
      setLoading(false);
    }
  };

  const startRename = (session: SessionVO) => {
    setEditingSessionId(session.id);
    setEditingTitle(session.title);
  };

  const commitRename = async () => {
    const id = editingSessionId;
    const title = editingTitle.trim();
    setEditingSessionId(null);
    if (id === null || !title) {
      return;
    }
    try {
      const updated = await api.renameSession(id, title);
      setSessions((prev) =>
        prev.map((s) => (s.id === id ? { ...s, title: updated.title } : s)),
      );
    } catch (err) {
      setError((err as ApiError).message);
    }
  };

  const cancelRename = () => {
    setEditingSessionId(null);
    setEditingTitle('');
  };

  const handleCloseSession = async (session: SessionVO) => {
    setConfirmingArchiveId(null);
    try {
      await api.closeSession(session.id);
      setSessions((prev) => prev.filter((s) => s.id !== session.id));
      if (selectedSessionId === session.id) {
        setSelectedSessionId(null);
      }
    } catch (err) {
      setError((err as ApiError).message);
    }
  };

  const respondClarification = async (response: string) => {
    const c = clarification;
    if (!c) {
      return;
    }
    setClarification(null);
    setClarificationText('');
    const agent = agentsRef.current[String(selectedAgentId)];
    if (!agent) {
      return;
    }
    agent.addMessage(buildLiveClarificationMessage(c, response));
    agent.addMessage({
      id: `u-${Date.now()}`,
      role: 'user',
      content: response,
    });
    try {
      await agent.runAgent({
        resume: [
          { interruptId: c.clarificationId, status: 'resolved', payload: response },
        ],
      });
    } catch (err) {
      setError((err as Error).message);
    }
  };

  const cancelClarification = async () => {
    const c = clarification;
    if (!c) {
      return;
    }
    setClarification(null);
    setClarificationText('');
    const agent = agentsRef.current[String(selectedAgentId)];
    if (!agent) {
      return;
    }
    agent.addMessage(buildLiveClarificationMessage(c, '__dismissed__'));
    try {
      await agent.runAgent({
        resume: [{ interruptId: c.clarificationId, status: 'cancelled' }],
      });
    } catch (err) {
      setError((err as Error).message);
    }
  };

  // 停止：中止本地流 + session 级后端停止（不依赖 runId）
  const handleStop = async () => {
    const agent = agentsRef.current[String(selectedAgentId)];
    try {
      agent?.abortRun();
    } catch (e) {
      // ignore local abort errors
    }
    if (selectedSessionId != null) {
      try {
        await stopSession(apiBase, selectedSessionId);
      } catch (e) {
        console.warn('[AgentSphere] stop session failed:', e);
      }
    }
  };

  // 通知 chrome-extension 当前会话（sessionStorage 桥 + 即时事件）
  useEffect(() => {
    if (selectedSessionId !== null) {
      try {
        sessionStorage.setItem(ACTIVE_SESSION_KEY, String(selectedSessionId));
        window.dispatchEvent(
          new CustomEvent('agent-sphere:session-change', {
            detail: { sessionId: selectedSessionId },
          }),
        );
      } catch {
        // ignore storage unavailable
      }
    }
    return () => {
      try {
        sessionStorage.removeItem(ACTIVE_SESSION_KEY);
      } catch {
        // ignore
      }
    };
  }, [selectedSessionId]);

  // 加载当前会话历史（第一页=最新），并清掉上个会话残留消息
  useEffect(() => {
    if (selectedAgentId === null || selectedSessionId === null) {
      return;
    }
    const agent = agentsRef.current[String(selectedAgentId)];
    if (!agent) {
      return;
    }
    let cancelled = false;
    agent.setMessages([]);
    historyPageRef.current = 2;
    setHasMoreHistory(false);
    setLoadingHistory(true);
    setTodos([]);
    (async () => {
      try {
        const [res, todoList] = await Promise.all([
          api.listRuns(selectedSessionId, 1, HISTORY_PAGE_SIZE),
          api.getTodos(selectedSessionId).catch(() => [] as SessionTodoVO[]),
        ]);
        if (cancelled) {
          return;
        }
        agent.setMessages(toChatMessages(res.records.slice().reverse()));
        setHasMoreHistory(res.records.length >= HISTORY_PAGE_SIZE);
        setTodos(todoList);
      } catch {
        // 历史加载失败不影响新对话
      } finally {
        if (!cancelled) {
          setLoadingHistory(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [selectedAgentId, selectedSessionId, api]);

  // 被动 reasoning 流：任务触发（或其他非本 widget 发起）的 run 的 thinking 实时注入 chatbox
  useEffect(() => {
    if (selectedSessionId === null || selectedAgentId === null) {
      return;
    }
    const agent = agentsRef.current[String(selectedAgentId)];
    if (!agent) {
      return;
    }
    const injected = new Set<string>();
    const controller = new AbortController();
    void connectReasoningStream({
      url: `${apiBase}/runtime/${selectedSessionId}/stream`,
      token: user.token,
      signal: controller.signal,
      onOpen: () => {
        console.log('[AgentSphere] reasoning stream connected for session', selectedSessionId);
      },
      onReasoning: (runId, delta) => {
        if (ownRunIdsRef.current.has(runId)) {
          return;
        }
        // 任务 run 推理开始 → 置流式态，让 reasoning 块展开显示 "Thinking…"
        if (!agent.isRunning || streamingRunIdRef.current !== runId) {
          streamingRunIdRef.current = runId;
          agent.isRunning = true;
        }
        const id = `reasoning-${runId}`;
        if (injected.has(id)) {
          const updated = [...agent.messages];
          const reasoningMsg = updated.find((m) => m.id === id) as
            | { id: string; role: string; content: string }
            | undefined;
          if (reasoningMsg) {
            reasoningMsg.content += delta;
            agent.setMessages(updated);
          } else {
            // 历史重载清掉了进行中的消息 → 重新创建
            agent.addMessage({ id, role: 'reasoning', content: delta });
          }
          return;
        }
        if (agent.messages.some((m) => m.id === id)) {
          // CopilotKit 已自行渲染该 reasoning（本 widget 发起的 run）——跳过
          return;
        }
        injected.add(id);
        agent.addMessage({ id, role: 'reasoning', content: delta });
      },
      onRunEnded: (runId) => {
        if (streamingRunIdRef.current === runId) {
          streamingRunIdRef.current = null;
          agent.isRunning = false;
        }
      },
    });
    return () => {
      controller.abort();
      streamingRunIdRef.current = null;
      agent.isRunning = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedSessionId, selectedAgentId, apiBase, user.token]);

  const loadOlder = useCallback(async () => {
    if (loadingHistory || !hasMoreHistory) {
      return;
    }
    if (selectedAgentId === null || selectedSessionId === null) {
      return;
    }
    const agent = agentsRef.current[String(selectedAgentId)];
    if (!agent) {
      return;
    }
    setLoadingHistory(true);
    try {
      const res = await api.listRuns(
        selectedSessionId,
        historyPageRef.current,
        HISTORY_PAGE_SIZE,
      );
      const older = toChatMessages(res.records.slice().reverse());
      const current = agent.messages as ChatMessage[];
      agent.setMessages([...older, ...current]);
      historyPageRef.current += 1;
      setHasMoreHistory(res.records.length >= HISTORY_PAGE_SIZE);
    } catch {
      // ignore
    } finally {
      setLoadingHistory(false);
    }
  }, [loadingHistory, hasMoreHistory, selectedAgentId, selectedSessionId, api]);

  const handleHistoryScrollUp = (event: UIEvent<HTMLDivElement>) => {
    const el = event.target as HTMLElement;
    if (el.scrollHeight > el.clientHeight && el.scrollTop < SCROLL_TOP_THRESHOLD) {
      void loadOlder();
    }
  };

  const handleAgentScroll = (event: UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    if (
      el.scrollHeight - el.scrollTop - el.clientHeight < SCROLL_END_THRESHOLD &&
      hasMoreAgents &&
      !loadingAgents
    ) {
      void loadAgents(agentPage);
    }
  };

  const handleSessionScroll = (event: UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    if (
      el.scrollHeight - el.scrollTop - el.clientHeight < SCROLL_END_THRESHOLD &&
      hasMoreSessions &&
      !loadingSessions
    ) {
      void loadMoreSessions(sessionOffset);
    }
  };

  const selectedSession = sessions.find((s) => s.id === selectedSessionId) ?? null;
  const selectedAgent = instances.find((i) => i.id === selectedAgentId) ?? null;
  const visibleSessions = selectedAgentId
    ? sessions.filter((s) => s.agentInstanceId === selectedAgentId)
    : [];

  return (
    <div className="aw-view">
      <div className="aw-sidebar">
        <div className="aw-sidebar-title">实例</div>
        <div ref={agentListRef} className="aw-list aw-agent-list" onScroll={handleAgentScroll}>
          {instances.length === 0 && !loadingAgents ? (
            <div className="aw-item-hint">暂无实例</div>
          ) : null}
          {instances.map((i) => (
            <div
              key={i.id}
              className={
                i.id === selectedAgentId ? 'aw-item aw-item-active' : 'aw-item'
              }
              onClick={() => setSelectedAgentId(i.id)}
            >
              {i.name}
            </div>
          ))}
          {loadingAgents ? <div className="aw-item-hint">加载中…</div> : null}
        </div>
        <div className="aw-sidebar-title">会话</div>
        <div ref={sessionListRef} className="aw-list aw-session-list" onScroll={handleSessionScroll}>
          {visibleSessions.length === 0 && !loadingSessions ? (
            <div className="aw-item-hint">暂无会话，请新建</div>
          ) : null}
          {visibleSessions.map((s) =>
            editingSessionId === s.id ? (
              <div
                key={s.id}
                className={
                  s.id === selectedSessionId ? 'aw-item aw-item-active' : 'aw-item'
                }
              >
                <input
                  className="aw-item-rename-input"
                  value={editingTitle}
                  maxLength={50}
                  autoFocus
                  onChange={(e) => setEditingTitle(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      void commitRename();
                    } else if (e.key === 'Escape') {
                      cancelRename();
                    }
                  }}
                  onBlur={cancelRename}
                  onClick={(e) => e.stopPropagation()}
                  onDoubleClick={(e) => e.stopPropagation()}
                />
                <span
                  className="aw-item-edit aw-item-ok"
                  title="提交"
                  role="button"
                  aria-label="提交重命名"
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={(e) => {
                    e.stopPropagation();
                    void commitRename();
                  }}
                >
                  ✓
                </span>
                <span
                  className="aw-item-edit aw-item-cancel"
                  title="取消"
                  role="button"
                  aria-label="取消重命名"
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={(e) => {
                    e.stopPropagation();
                    cancelRename();
                  }}
                >
                  ✕
                </span>
              </div>
            ) : (
              <div
                key={s.id}
                className={
                  s.id === selectedSessionId ? 'aw-item aw-item-active' : 'aw-item'
                }
                onClick={() => setSelectedSessionId(s.id)}
              >
                <span className="aw-item-label">{s.title}</span>
                {confirmingArchiveId === s.id ? (
                  <>
                    <span className="aw-archive-hint">归档？</span>
                    <span
                      className="aw-item-edit aw-item-ok"
                      title="确认归档"
                      role="button"
                      aria-label="确认归档"
                      onClick={(e) => {
                        e.stopPropagation();
                        void handleCloseSession(s);
                      }}
                    >
                      ✓
                    </span>
                    <span
                      className="aw-item-edit aw-item-cancel"
                      title="取消"
                      role="button"
                      aria-label="取消归档"
                      onClick={(e) => {
                        e.stopPropagation();
                        setConfirmingArchiveId(null);
                      }}
                    >
                      ✕
                    </span>
                  </>
                ) : (
                  <>
                    <span
                      className="aw-item-edit"
                      title="重命名"
                      role="button"
                      aria-label="重命名会话"
                      onClick={(e) => {
                        e.stopPropagation();
                        startRename(s);
                      }}
                    >
                      ✎
                    </span>
                    <span
                      className="aw-item-edit aw-item-archive"
                      title="归档"
                      role="button"
                      aria-label="归档会话"
                      onClick={(e) => {
                        e.stopPropagation();
                        setConfirmingArchiveId(s.id);
                      }}
                    >
                      🗑
                    </span>
                  </>
                )}
              </div>
            ),
          )}
          {loadingSessions ? <div className="aw-item-hint">加载中…</div> : null}
        </div>
        <div className="aw-user" title={user.email || user.username}>
          {user.ssoProviderCode && user.ssoSubject
            ? `${user.ssoProviderCode}@${user.ssoSubject}`
            : user.englishName || user.displayName || user.username}
        </div>
        <button
          type="button"
          className="aw-new-session"
          onClick={() => void handleCreateSession()}
        >
          新会话
        </button>
      </div>

      <div className="aw-main">
        <div className="aw-header">
          <span className="aw-title">{selectedAgent?.name ?? '选择实例'}</span>
        </div>

        {error ? <div className="aw-error">{error}</div> : null}
        {loading ? <div className="aw-loading">加载中…</div> : null}

        {selectedSessionId !== null && selectedSession && selectedAgentId !== null ? (
          <div className="aw-chat">
            <CopilotKit
              runtimeUrl={undefined}
              selfManagedAgents={agents}
              headers={{ Authorization: `Bearer ${user.token}` }}
            >
              <ChatAuxPanel agentId={String(selectedAgentId)} initialTodos={todos} />
              {hasMoreHistory ? (
                <button
                  type="button"
                  className="aw-load-more"
                  onClick={() => void loadOlder()}
                  disabled={loadingHistory}
                >
                  {loadingHistory ? '正在加载更早消息…' : '加载更早消息'}
                </button>
              ) : null}
              <div className="aw-chat-body" onScroll={handleHistoryScrollUp}>
                <CopilotChat
                  key={String(selectedSession.id)}
                  agentId={String(selectedAgentId)}
                  threadId={String(selectedSession.id)}
                  onStop={handleStop}
                />
              </div>
            </CopilotKit>
            {clarification ? (
              <div className="aw-clarification">
                <div className="aw-clarification-title">{clarification.title}</div>
                {clarification.options.length > 0 ? (
                  <div className="aw-clarification-options">
                    {clarification.options.map((opt) => (
                      <button
                        key={opt.value}
                        type="button"
                        className="aw-clarification-option"
                        onClick={() => void respondClarification(opt.value)}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="aw-clarification-text">
                    <input
                      value={clarificationText}
                      maxLength={200}
                      placeholder="请输入你的回答…"
                      onChange={(e) => setClarificationText(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && clarificationText.trim()) {
                          void respondClarification(clarificationText.trim());
                        }
                      }}
                    />
                    <button
                      type="button"
                      className="aw-clarification-option"
                      disabled={!clarificationText.trim()}
                      onClick={() => void respondClarification(clarificationText.trim())}
                    >
                      确认
                    </button>
                  </div>
                )}
                <button
                  type="button"
                  className="aw-clarification-cancel"
                  onClick={() => void cancelClarification()}
                >
                  取消
                </button>
              </div>
            ) : null}
          </div>
        ) : (
          <div className="aw-empty">
            {!loading ? '选择或新建一个会话开始对话' : ''}
          </div>
        )}
      </div>
    </div>
  );
}

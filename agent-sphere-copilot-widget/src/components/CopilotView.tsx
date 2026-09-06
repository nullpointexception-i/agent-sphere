import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { UIEvent } from 'react';
import { HttpAgent, type AbstractAgent } from '@ag-ui/client';
import {
  CopilotChat,
  CopilotKit,
  type CopilotChatReasoningMessageProps,
} from '@copilotkit/react-core/v2';
import { ApiClient, ApiError, createApi, stopSession } from '../api';
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

/** 列表默认离行（不含 reasoning）：批量补拉本批 run 的推理后回填。 */
async function fillHistoryReasoning(api: ApiClient, runs: RunVO[]): Promise<void> {
  const ids = runs.map((r) => r.id).filter((id): id is number => id != null);
  if (ids.length === 0) {
    return;
  }
  try {
    const reasonMap = await api.runsReasoning(ids);
    for (const r of runs) {
      const reasoning = reasonMap[String(r.id)];
      if (reasoning) {
        r.reasoning = reasoning;
      }
    }
  } catch {
    // 推理补拉失败不影响历史消息展示
  }
}

/** 拉取本批 run 的 activities（interaction 级 timeline 数据源）。 */
async function loadActivitiesForRuns(
  api: ApiClient,
  sessionId: number,
  runs: RunVO[],
): Promise<Record<number, any[]>> {
  const map: Record<number, any[]> = {};
  await Promise.all(
    runs
      .map((r) => r.id)
      .filter((id): id is number => id != null)
      .map(async (id) => {
        try {
          const res = await api.runActivities(id, sessionId, 0, 100);
          map[id] = Array.isArray(res?.records) ? res.records : [];
        } catch {
          map[id] = [];
        }
      }),
  );
  return map;
}

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

interface SkillReasonBlock {
  skillId: string;
  name: string;
  content: string;
}

const SKILL_REASON_MARKER_RE = /▶\s*Skill\s+(\d+)\s*:\s*([^\n]*)/;

/** 与主 UI 一致的哨兵切分：拆成「主 Agent 段 + 按时序子 Agent 段」。 */
function splitSkillSegments(raw: string): { main: string; blocks: SkillReasonBlock[] } {
  if (!raw) {
    return { main: '', blocks: [] };
  }
  const blocks: SkillReasonBlock[] = [];
  let lastEnd = 0;
  let nextIsContent = false;
  const re = new RegExp(SKILL_REASON_MARKER_RE, 'g');
  let m: RegExpExecArray | null = re.exec(raw);
  let mainText = '';
  while (m !== null) {
    const markerStart = m.index;
    if (!nextIsContent && lastEnd < markerStart) {
      mainText += raw.substring(lastEnd, markerStart);
    } else if (nextIsContent && lastEnd < markerStart) {
      blocks[blocks.length - 1].content += raw.substring(lastEnd, markerStart);
    }
    blocks.push({ skillId: m[1], name: m[2] || m[1], content: '' });
    nextIsContent = true;
    lastEnd = re.lastIndex;
    m = re.exec(raw);
  }
  if (nextIsContent && lastEnd < raw.length) {
    blocks[blocks.length - 1].content += raw.substring(lastEnd);
  } else if (!nextIsContent) {
    mainText = raw;
  }
  return { main: mainText.trim(), blocks };
}

function toChatMessages(
  runs: RunVO[],
  activitiesByRun?: Record<number, any[]>,
): ChatMessage[] {
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
    // interaction 级 timeline（若已拉取 activities）
    const acts = activitiesByRun ? activitiesByRun[r.id] : undefined;
    if (acts && acts.length > 0) {
      // activities 为 created_at DESC → 转正序后归组
      const list = [...acts].reverse();
      const interactions: { reason: string; reply: string; tools: string[] }[] = [];
      for (const act of list) {
        if (!act) continue;
        if (act.activityType === 'llm_interaction') {
          interactions.push({
            reason: act.reasoning || '',
            reply: act.replyContent || act.reply || '',
            tools: [],
          });
        } else if (act.activityType === 'tool_call') {
          const cur = interactions[interactions.length - 1];
          const line = `${act.displayNameCn || act.toolName || 'tool'}${act.toolStatus ? ` [${act.toolStatus}]` : ''}`;
          if (cur) {
            cur.tools.push(line);
          } else {
            interactions.push({ reason: '', reply: '', tools: [line] });
          }
        }
      }
      interactions.forEach((it, i) => {
        const reasonText = [it.reason, ...(it.tools.length ? [`\n工具调用：${it.tools.join(', ')}`] : [])]
          .filter(Boolean)
          .join('\n');
        if (reasonText.trim()) {
          messages.push({
            id: `r-${r.id}-${i}`,
            role: 'reasoning',
            content: reasonText,
          });
        }
        if (it.reply.trim()) {
          messages.push({
            id: `a-${r.id}-${i}`,
            role: 'assistant',
            content: it.reply,
          });
        }
      });
      // 澄清块单独输出（HITL 交互独立于模型 reply）
      if (block) {
        messages.push({ id: `a-${r.id}-cls`, role: 'assistant', content: block });
      }
      continue; // 已按 interaction 输出，不再单独输出 assistantReply
    }
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

const REASONING_MAX_HEIGHT = 320;
const SKILL_REASON_MAX_HEIGHT = 200;

const NEAR_BOTTOM_THRESHOLD = 24;

function isNearBottom(el: HTMLElement | null): boolean {
  if (!el) return false;
  return el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_THRESHOLD;
}

function scrollToBottom(el: HTMLElement | null) {
  if (!el) return;
  window.requestAnimationFrame(() => {
    if (el.isConnected) el.scrollTop = el.scrollHeight;
  });
}

/** CopilotKit reasoningMessage 槽位：主 Agent 段 + 内嵌子 Agent(skill) 折叠块。 */
function SkillReasoningMessage(props: CopilotChatReasoningMessageProps) {
  const message = props.message as { id?: string; role?: string; content?: string };
  const messages = (props.messages ?? []) as { id?: string }[];
  const isLatest = messages.length > 0 && messages[messages.length - 1]?.id === message.id;
  const streaming = !!(props.isRunning && isLatest);
  const content = typeof message.content === 'string' ? message.content : '';
  const [openKeys, setOpenKeys] = useState<Record<string, boolean>>({});
  const [wholeOpen, setWholeOpen] = useState(streaming);
  const mainRef = useRef<HTMLDivElement | null>(null);
  const blockRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const prevOpenKeysRef = useRef<Record<string, boolean>>({});

  const split = useMemo(() => splitSkillSegments(content), [content]);
  useEffect(() => {
    if (streaming) setWholeOpen(true);
  }, [streaming]);

  const mainLen = split.main.length;
  const blocksLen = split.blocks.map((b) => b.content.length).join('-');

  // 主段：内容增长且用户停留在容器底部附近时自动滚到底
  useEffect(() => {
    if (wholeOpen && isNearBottom(mainRef.current)) {
      scrollToBottom(mainRef.current);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wholeOpen, mainLen]);

  // 子块：打开状态下内容增长滚动到底；刚展开的块强制滚到底
  useEffect(() => {
    Object.keys(openKeys).forEach((k) => {
      if (!openKeys[k]) return;
      const el = blockRefs.current[k];
      if (!el) return;
      if (isNearBottom(el) || !prevOpenKeysRef.current[k]) {
        scrollToBottom(el);
      }
    });
    prevOpenKeysRef.current = { ...openKeys };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openKeys, blocksLen]);

  return (
    <div
      style={{
        fontSize: 12,
        color: '#6b7280',
        borderLeft: '2px solid #e5e7eb',
        paddingLeft: 8,
        margin: '4px 0',
      }}
    >
      <button
        type="button"
        onClick={() => setWholeOpen((v) => !v)}
        style={{
          cursor: 'pointer',
          background: 'none',
          border: 'none',
          padding: 0,
          fontSize: 11,
          fontWeight: 500,
          color: '#9ca3af',
        }}
      >
        {streaming ? 'Thinking…' : 'Reasoning'}
        {' '}
        {wholeOpen ? '▾' : '▸'}
      </button>
      {wholeOpen && (
        <div
          ref={mainRef}
          style={{ maxHeight: REASONING_MAX_HEIGHT, overflowY: 'auto', marginTop: 4 }}
        >
          {split.main ? <div style={{ whiteSpace: 'pre-wrap' }}>{split.main}</div> : null}
          {split.blocks.map((b, i) => {
            const k = `${b.skillId}-${i}`;
            const isOpen = !!openKeys[k];
            return (
              <div key={k} style={{ marginTop: 6 }}>
                <button
                  type="button"
                  onClick={() => setOpenKeys((p) => ({ ...p, [k]: !p[k] }))}
                  style={{
                    cursor: 'pointer',
                    background: 'none',
                    border: 'none',
                    padding: 0,
                    fontSize: 11,
                    color: '#2563eb',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 4,
                  }}
                >
                  <span>{isOpen ? '▾' : '▸'}</span>
                  <span>⚙️ Skill {b.name}</span>
                </button>
                {isOpen && (
                  <div
                    ref={(el) => {
                      blockRefs.current[k] = el;
                    }}
                    style={{
                      maxHeight: SKILL_REASON_MAX_HEIGHT,
                      overflowY: 'auto',
                      marginTop: 4,
                      whiteSpace: 'pre-wrap',
                      color: '#8b8b8b',
                    }}
                  >
                    {b.content}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** 后端返回的相对路径按 apiBase 所在源解析为绝对地址；外链原样返回。 */
/** 后端返回的相对路由按 apiBase 前缀解析为绝对地址（相对/绝对 apiBase 均正确）；外链原样返回。 */
function resolveAbsoluteUrl(raw: string, apiBase: string): string {
  if (/^https?:\/\//i.test(raw)) {
    return raw;
  }
  const clean = raw.replace(/^\/+/, '');
  if (/^https?:\/\//i.test(apiBase)) {
    const base = apiBase.endsWith('/') ? apiBase : `${apiBase}/`;
    return new URL(clean, base).toString();
  }
  const base = apiBase.endsWith('/') ? apiBase : `${apiBase}/`;
  return `${window.location.origin}${base}${clean}`;
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
  // 插件下载入口（应用市场 + 站内，配置覆盖优先，其次运行时公开配置拉取）
  const [pluginStoreUrl, setPluginStoreUrl] = useState<string>('');
  const [pluginDownloadUrl, setPluginDownloadUrl] = useState<string>('');
  const [pluginMenuOpen, setPluginMenuOpen] = useState(false);
  // 子 Agent 实时聚合（SSE）与历史
  const [subAgentLive, setSubAgentLive] = useState<any[]>([]);
  const [subAgentHistorical, setSubAgentHistorical] = useState<any[]>([]);
  const [subAgentModal, setSubAgentModal] = useState<{
    title: string;
    live?: any;
    timeline: any[];
  } | null>(null);
  // 主 Agent live interaction 级 timeline（全部 run：自发起 + 被动）
  const [mainTimeline, setMainTimeline] = useState<any[]>([]);
  const mainTimelineSeqRef = useRef<Record<string, number>>({});

  useEffect(() => {
    const resolveUrl = async () => {
      try {
        const res = await api.publicConfig(['plugin.download-url', 'plugin.store-url']);
        setPluginDownloadUrl(config.pluginDownloadUrl || resolveAbsoluteUrl(res?.['plugin.download-url'] || '', apiBase));
        setPluginStoreUrl(resolveAbsoluteUrl(res?.['plugin.store-url'] || '', apiBase));
      } catch {
        // 拉取失败仅保留配置覆盖
        setPluginDownloadUrl(config.pluginDownloadUrl || '');
      }
    };
    void resolveUrl();
  }, [config.pluginDownloadUrl, api, apiBase]);

  useEffect(() => {
    if (!pluginMenuOpen) {
      return;
    }
    const close = () => setPluginMenuOpen(false);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [pluginMenuOpen]);

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
    setSubAgentLive([]);
    setSubAgentHistorical([]);
    (async () => {
      try {
        const [res, todoList] = await Promise.all([
          api.listRuns(selectedSessionId, 1, HISTORY_PAGE_SIZE),
          api.getTodos(selectedSessionId).catch(() => [] as SessionTodoVO[]),
        ]);
        if (cancelled) {
          return;
        }
        await fillHistoryReasoning(api, res.records);
        const actsMap = await loadActivitiesForRuns(
          api,
          selectedSessionId,
          res.records,
        );
        if (cancelled) {
          return;
        }
        agent.setMessages(
          toChatMessages(res.records.slice().reverse(), actsMap),
        );
        setHasMoreHistory(res.records.length >= HISTORY_PAGE_SIZE);
        setTodos(todoList);
        // 子 Agent 历史回放
        try {
          const subRuns = await api.listSubAgentRuns(selectedSessionId);
          if (!cancelled) setSubAgentHistorical(subRuns || []);
        } catch {
          // 忽略
        }
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
      onReasoning: (runId, delta, nodeName) => {
        if (ownRunIdsRef.current.has(runId)) {
          return;
        }
        // 子 Agent thinking：路由进子 Agent 卡片（不进主 reasoning 气泡）
        const isSub = !!nodeName && nodeName.startsWith('skill:');
        if (isSub && nodeName) {
          const skillId = nodeName.slice('skill:'.length);
          const key = `w-${runId}-${skillId}`;
          setSubAgentLive((prev) => {
            const exists = prev.find((s) => s.key === key);
            if (exists) {
              return prev.map((s) =>
                s.key === key ? { ...s, reasoning: s.reasoning + delta } : s,
              );
            }
            return [
              ...prev,
              {
                key,
                runId,
                skillId,
                name: `Skill ${skillId}`,
                status: 'RUNNING',
                reasoning: delta,
                reply: '',
                toolCalls: [],
              },
            ];
          });
          return;
        }
        // 主 Agent 被动 run 推理 → interaction 级 timeline（不外提 CopilotKit reasoning 消息）
        if (!ownRunIdsRef.current.has(runId)) {
          const rid = runId;
          setMainTimeline((prev) => {
            const cur = mainTimelineSeqRef.current[rid] ?? 0;
            const hasCur = prev.some(
              (it) => String(it.runId) === rid && it.seq === cur,
            );
            const curHasTool =
              hasCur &&
              prev
                .filter((it) => String(it.runId) === rid && it.seq === cur)
                .some((it) => it.tools?.length > 0);
            let seq = cur;
            if (!hasCur || curHasTool) {
              seq = cur + 1;
              mainTimelineSeqRef.current[rid] = seq;
            }
            const key = `m-${rid}-${seq}`;
            const existing = prev.find((it) => it.key === key);
            if (existing) {
              return prev.map((it) =>
                it.key === key
                  ? { ...it, reason: it.reason + delta }
                  : it,
              );
            }
            return [
              ...prev,
              {
                key,
                runId: rid,
                seq,
                reason: delta,
                reply: '',
                tools: [],
                status: 'RUNNING',
                ts: Date.now(),
              },
            ];
          });
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
        // 主 Agent timeline 收口
        setMainTimeline((prev) =>
          prev.map((it) =>
            String(it.runId) === String(runId)
              ? { ...it, status: 'COMPLETED' }
              : it,
          ),
        );
      },
      onEvent: (parsed) => {
        const evtType = String(parsed.eventType || parsed.type || '');
        const d = (parsed.data || parsed) as Record<string, any>;
        const nodeName: unknown = d?.nodeName;
        const isSub =
          String(nodeName || '').startsWith('skill:') ||
          d?.subAgentRunId != null;
        const runId = d?.runId != null ? String(d.runId) : '';
        if (!runId) return;
        // 主 Agent 被动 run 的 reply / tool_call → mainTimeline
        if (!isSub && !ownRunIdsRef.current.has(runId)) {
          const rid = runId;
          if (evtType === 'content_token') {
            const delta = String(d?.response || '');
            if (!delta) return;
            setMainTimeline((prev) => {
              const cur = mainTimelineSeqRef.current[rid] ?? 0;
              const hasCur = prev.some(
                (it) => String(it.runId) === rid && it.seq === cur,
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
                  },
                ];
                mainTimelineSeqRef.current[rid] = 0;
              }
              const key = `m-${rid}-${seq}`;
              return base.map((it) =>
                it.key === key ? { ...it, reply: it.reply + delta } : it,
              );
            });
            return;
          }
          // tool_call 状态（reasoning_token 包装）
          const toolStatus = String(d?.reasoningSubType || '');
          if (toolStatus.startsWith('tool_call_')) {
            const tool = {
              callId: String(d?.publishId || `${rid}-${Date.now()}`),
              name: String(d?.toolName || d?.displayNameCn || 'tool'),
              args: String(d?.argumentsJson || ''),
              status: toolStatus.replace('tool_call_', ''),
            };
            setMainTimeline((prev) => {
              const cur = mainTimelineSeqRef.current[rid] ?? 0;
              const hasCur = prev.some(
                (it) => String(it.runId) === rid && it.seq === cur,
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
                  },
                ];
                mainTimelineSeqRef.current[rid] = 0;
              }
              const key = `m-${rid}-${seq}`;
              return base.map((it) => {
                if (it.key !== key) return it;
                const idx = it.tools.findIndex(
                  (t: any) => t.callId === tool.callId,
                );
                if (idx >= 0) {
                  const next = [...it.tools];
                  next[idx] = { ...next[idx], ...tool };
                  return { ...it, tools: next };
                }
                return { ...it, tools: [...it.tools, tool] };
              });
            });
            return;
          }
          return;
        }
        const skillId = String(nodeName || '')
          .replace(/^skill:/, '')
          .concat(d?.subAgentRunId != null ? `-${d.subAgentRunId}` : '');
        const key = `w-${runId}-${skillId}`;
        const ensure = (prev: any[]) => {
          const exists = prev.find((s) => s.key === key);
          return exists
            ? prev
            : [
                ...prev,
                {
                  key,
                  runId,
                  skillId,
                  name: `Skill ${skillId}`,
                  status: 'RUNNING',
                  reasoning: '',
                  reply: '',
                  toolCalls: [],
                },
              ];
        };
        if (evtType === 'content_token') {
          const delta = String(d?.response || '');
          setSubAgentLive((prev) =>
            ensure(prev).map((s) =>
              s.key === key ? { ...s, reply: s.reply + delta } : s,
            ),
          );
        } else if (
          evtType === 'reasoning_token' &&
          d?.reasoningSubType === 'model_reason'
        ) {
          const delta = String(d?.response || '');
          setSubAgentLive((prev) =>
            ensure(prev).map((s) =>
              s.key === key ? { ...s, reasoning: s.reasoning + delta } : s,
            ),
          );
        } else if (
          (d?.reasoningSubType as string)?.startsWith('tool_call_')
        ) {
          const status = String(d?.reasoningSubType).replace('tool_call_', '');
          const tool = {
            callId: String(d?.publishId || `${key}-${Date.now()}`),
            toolName: String(d?.toolName || d?.displayNameCn || 'tool'),
            args: String(d?.argumentsJson || ''),
            status,
          };
          setSubAgentLive((prev) =>
            ensure(prev).map((s) => {
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
            }),
          );
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
      await fillHistoryReasoning(api, res.records);
      const actsMap = await loadActivitiesForRuns(
        api,
        selectedSessionId,
        res.records,
      );
      const older = toChatMessages(res.records.slice().reverse(), actsMap);
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
        {pluginStoreUrl || pluginDownloadUrl ? (
          <div className="aw-plugin-download-wrap">
            {pluginMenuOpen && (pluginStoreUrl || pluginDownloadUrl) ? (
              <div className="aw-plugin-menu" role="menu">
                {pluginStoreUrl ? (
                  <a
                    className="aw-plugin-menu-item"
                    href={pluginStoreUrl}
                    target="_blank"
                    rel="noreferrer"
                    role="menuitem"
                  >
                    从应用市场下载
                  </a>
                ) : null}
                {pluginDownloadUrl ? (
                  <a
                    className="aw-plugin-menu-item"
                    href={pluginDownloadUrl}
                    target="_blank"
                    rel="noreferrer"
                    role="menuitem"
                  >
                    站内下载
                  </a>
                ) : null}
              </div>
            ) : null}
            <a
              className="aw-plugin-download"
              href={pluginDownloadUrl || pluginStoreUrl || '#'}
              target="_blank"
              rel="noreferrer"
              title="Chrome 插件下载"
              onClick={(
                e: React.MouseEvent<HTMLAnchorElement>,
              ) => {
                e.preventDefault();
                e.stopPropagation();
                setPluginMenuOpen((open) => !open);
              }}
            >
              插件下载
              <span className="aw-plugin-caret">▾</span>
            </a>
          </div>
        ) : null}
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
                  messageView={{
                    reasoningMessage: SkillReasoningMessage as never,
                  }}
                />
                {mainTimeline.length > 0 ? (
                  <div
                    style={{
                      borderTop: '1px dashed #e5e7eb',
                      padding: '8px 4px',
                      fontSize: 12,
                      color: '#4b5563',
                    }}
                  >
                    <div
                      style={{
                        fontSize: 10,
                        color: '#9ca3af',
                        marginBottom: 6,
                        fontWeight: 600,
                      }}
                    >
                      Agent Timeline（按交互）
                    </div>
                    {[...mainTimeline]
                      .sort((a, b) => (b.seq || 0) - (a.seq || 0))
                      .map((it) => (
                        <div
                          key={it.key}
                          style={{
                            border: '1px solid #eef0f3',
                            borderLeft: '3px solid #1677ff',
                            borderRadius: 6,
                            padding: '6px 8px',
                            marginBottom: 6,
                            background: '#f9fafb',
                          }}
                        >
                          <div
                            style={{
                              fontSize: 10,
                              color: '#9ca3af',
                              marginBottom: 4,
                              display: 'flex',
                              alignItems: 'center',
                              gap: 6,
                            }}
                          >
                            <span>🧭 Interaction #{it.seq + 1}</span>
                            <span>{it.status || ''}</span>
                          </div>
                          {it.reason && it.reason.trim() ? (
                            <details style={{ marginBottom: 4 }}>
                              <summary
                                style={{
                                  cursor: 'pointer',
                                  color: '#6b7280',
                                  fontStyle: 'italic',
                                  fontSize: 11,
                                }}
                              >
                                推理
                              </summary>
                              <pre
                                style={{
                                  whiteSpace: 'pre-wrap',
                                  maxHeight: 160,
                                  overflowY: 'auto',
                                  background: '#fff',
                                  padding: 6,
                                  borderRadius: 4,
                                  fontSize: 11,
                                  color: '#6b7280',
                                }}
                              >
                                {it.reason}
                              </pre>
                            </details>
                          ) : null}
                          {it.tools && it.tools.length > 0 ? (
                            <div style={{ margin: '4px 0' }}>
                              {it.tools.map((t: any, ti: number) => (
                                <div
                                  key={`${t.callId}-${ti}`}
                                  style={{
                                    fontSize: 11,
                                    display: 'flex',
                                    alignItems: 'center',
                                    gap: 6,
                                    padding: '2px 4px',
                                  }}
                                >
                                  <span>🛠️</span>
                                  <span>{t.name}</span>
                                  <span
                                    style={{
                                      marginLeft: 'auto',
                                      color: '#9ca3af',
                                    }}
                                  >
                                    {t.status || ''}
                                  </span>
                                </div>
                              ))}
                            </div>
                          ) : null}
                          {it.reply ? (
                            <div
                              style={{
                                marginTop: 4,
                                fontSize: 12,
                                color: '#374151',
                                whiteSpace: 'pre-wrap',
                              }}
                            >
                              {it.reply}
                            </div>
                          ) : null}
                        </div>
                      ))}
                  </div>
                ) : null}
              </div>
            </CopilotKit>
            {subAgentLive.length + subAgentHistorical.length > 0 ? (
              <div
                style={{
                  borderTop: '1px solid #eef0f3',
                  padding: '6px 10px',
                  background: '#fafafa',
                  fontSize: 12,
                  color: '#595959',
                }}
              >
                <div style={{ fontSize: 10, color: '#9ca3af', marginBottom: 4 }}>
                  子 Agent（Sub-Agent）
                </div>
                <div style={{ display: 'flex', gap: 6, overflowX: 'auto' }}>
                  {subAgentLive.map((s) => (
                    <button
                      key={s.key}
                      type="button"
                      onClick={() =>
                        setSubAgentModal({
                          title: s.name,
                          live: s,
                          timeline: [],
                        })
                      }
                      style={{
                        cursor: 'pointer',
                        border: '1px solid #e5e7eb',
                        borderRadius: 6,
                        background: '#fff',
                        padding: '3px 8px',
                        whiteSpace: 'nowrap',
                        fontSize: 11,
                      }}
                    >
                      ⚙️ {s.name}
                      <span style={{ marginLeft: 4, color: '#9ca3af' }}>
                        {s.status || 'RUNNING'}
                      </span>
                    </button>
                  ))}
                  {subAgentHistorical.map((h) => (
                    <button
                      key={`h-${h.id}`}
                      type="button"
                      onClick={() => {
                        api
                          .subAgentTimeline(h.id)
                          .then((tl) =>
                            setSubAgentModal({
                              title: h.displayName,
                              live: undefined,
                              timeline: tl || [],
                            }),
                          )
                          .catch(() =>
                            setSubAgentModal({
                              title: h.displayName,
                              live: undefined,
                              timeline: [],
                            }),
                          );
                      }}
                      style={{
                        cursor: 'pointer',
                        border: '1px solid #e5e7eb',
                        borderRadius: 6,
                        background: '#fff',
                        padding: '3px 8px',
                        whiteSpace: 'nowrap',
                        fontSize: 11,
                      }}
                    >
                      ⚙️ {h.displayName}
                      <span style={{ marginLeft: 4, color: '#9ca3af' }}>
                        {h.status || ''}
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            ) : null}
            {subAgentModal ? (
              <div
                style={{
                  position: 'fixed',
                  inset: 0,
                  background: 'rgba(0,0,0,0.4)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  zIndex: 9999,
                }}
                onClick={() => setSubAgentModal(null)}
              >
                <div
                  onClick={(e) => e.stopPropagation()}
                  style={{
                    background: '#fff',
                    borderRadius: 10,
                    width: 'min(88vw, 640px)',
                    maxHeight: '80vh',
                    display: 'flex',
                    flexDirection: 'column',
                  }}
                >
                  <div
                    style={{
                      padding: '10px 14px',
                      borderBottom: '1px solid #eef0f3',
                      fontWeight: 600,
                      fontSize: 14,
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                    }}
                  >
                    <span>⚙️ {subAgentModal.title}</span>
                    <button
                      type="button"
                      style={{
                        border: 'none',
                        background: 'none',
                        cursor: 'pointer',
                        fontSize: 16,
                        color: '#9ca3af',
                      }}
                      onClick={() => setSubAgentModal(null)}
                    >
                      ×
                    </button>
                  </div>
                  <div
                    style={{
                      padding: 12,
                      overflowY: 'auto',
                      fontSize: 12,
                      color: '#4b5563',
                    }}
                  >
                    <div style={{ fontWeight: 600, marginBottom: 4 }}>
                      Model Reply
                    </div>
                    <pre
                      style={{
                        whiteSpace: 'pre-wrap',
                        maxHeight: 160,
                        overflowY: 'auto',
                        background: '#f9fafb',
                        padding: 8,
                        borderRadius: 6,
                        fontSize: 12,
                      }}
                    >
                      {subAgentModal.live
                        ? subAgentModal.live.reply || '（无回复）'
                        : subAgentModal.timeline
                            .filter((t) => t.activityType === 'llm_interaction')
                            .map((t) => t.reply || '')
                            .filter(Boolean)
                            .join('\n\n') || '（无回复）'}
                    </pre>
                    <div style={{ fontWeight: 600, margin: '10px 0 4px' }}>
                      Tool Calls
                    </div>
                    {(
                      subAgentModal.live
                        ? subAgentModal.live.toolCalls
                        : subAgentModal.timeline.filter(
                            (t) => t.activityType === 'tool_call',
                          )
                    ).length === 0 ? (
                      <div style={{ color: '#9ca3af' }}>（无工具调用）</div>
                    ) : (
                      (subAgentModal.live
                        ? subAgentModal.live.toolCalls
                        : subAgentModal.timeline.filter(
                            (t) => t.activityType === 'tool_call',
                          )
                      ).map((t: any, i: number) => (
                        <div
                          key={`${t.callId ?? t.stepId}-${i}`}
                          style={{
                            border: '1px solid #eef0f3',
                            borderRadius: 6,
                            padding: 6,
                            marginBottom: 6,
                            fontSize: 12,
                          }}
                        >
                          <b>{t.toolName}</b>
                          <span style={{ marginLeft: 6, color: '#9ca3af' }}>
                            {t.status || t.toolStatus || ''}
                          </span>
                          {t.args || t.argumentsJson ? (
                            <pre
                              style={{
                                whiteSpace: 'pre-wrap',
                                maxHeight: 80,
                                overflowY: 'auto',
                                background: '#f9fafb',
                                padding: 4,
                                borderRadius: 4,
                                fontSize: 11,
                                margin: '4px 0 0',
                              }}
                            >
                              {t.args || t.argumentsJson}
                            </pre>
                          ) : null}
                        </div>
                      ))
                    )}
                    <div style={{ fontWeight: 600, margin: '10px 0 4px' }}>
                      Model Reason
                    </div>
                    <pre
                      style={{
                        whiteSpace: 'pre-wrap',
                        maxHeight: 160,
                        overflowY: 'auto',
                        background: '#f9fafb',
                        padding: 8,
                        borderRadius: 6,
                        fontStyle: 'italic',
                        color: '#6b7280',
                        fontSize: 12,
                      }}
                    >
                      {subAgentModal.live
                        ? subAgentModal.live.reasoning || '（无推理）'
                        : subAgentModal.timeline
                            .filter((t) => t.activityType === 'llm_interaction')
                            .map((t) => t.reasoning || '')
                            .filter(Boolean)
                            .join('\n\n') || '（无推理）'}
                    </pre>
                  </div>
                </div>
              </div>
            ) : null}
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

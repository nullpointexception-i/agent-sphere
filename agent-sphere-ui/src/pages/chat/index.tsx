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
import PiPWindow from './components/PiPWindow';
import SessionPanel from './components/SessionPanel';
import Sidebar from './components/Sidebar';
import { useStyles } from './style';

const SESSION_PAGE_SIZE = 10;

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
  const [latestScreenshot, setLatestScreenshot] = useState<string | null>(null);
  const [latestArtifact, setLatestArtifact] = useState<string | null>(null);

  const historyPageRef = useRef(1);
  const abortRef = useRef<AbortController | null>(null);
  const currentSessionIdRef = useRef<number | null>(null);
  const reconnectCountRef = useRef(0);
  const seenReplyRunIdsRef = useRef<Set<number>>(new Set());
  const reasoningSectionIdRef = useRef(0);
  const runUserMessageRef = useRef<Map<number, string>>(new Map());
  const currentRunIdRef = useRef<number | null>(null);
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

  // Listen for direct page_screenshot from extension (via executeScript → CustomEvent)
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.screenshot) {
        setLatestScreenshot(detail.screenshot);
        setLatestArtifact(JSON.stringify({ data: { url: detail.url || '' } }));
      }
    };
    window.addEventListener('page_screenshot', handler as EventListener);
    return () =>
      window.removeEventListener('page_screenshot', handler as EventListener);
  }, []);

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
      const res = await agentApi.runs.listBySession(sid, page, 3);
      const runs = (res.records || []).slice().reverse();
      if (runs.length < 3) setHasMoreHistory(false);
      historyPageRef.current = page + 1;
      const historyMsgs: any[] = [];
      const historyKeys: string[] = [];
      for (const r of runs) {
        if (r.assistantReply && seenReplyRunIdsRef.current.has(r.id)) continue;
        if (r.userMessage && r.userMessage !== '{}' && r.userMessage.trim()) {
          historyMsgs.push({
            role: 'user',
            content: r.userMessage,
            ts: new Date(r.createdAt).getTime(),
          });
        }
        const hasAssistantContent =
          r.assistantReply &&
          r.assistantReply !== '{}' &&
          r.assistantReply.trim();
        const hasClarifications =
          !!r.clarifications && r.clarifications.length > 0;
        if (hasAssistantContent || hasClarifications) {
          if (hasAssistantContent) seenReplyRunIdsRef.current.add(r.id);
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
            ts: new Date(r.createdAt).getTime(),
            clarifications,
          });
          historyKeys.push(`run-${r.id}`);
        }
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

            if (evtType === 'reasoning_token') {
              const raw = d?.response || '';
              const subType = d?.reasoningSubType || '';
              const publishId = d?.publishId;
              const runId = d?.runId;
              const isI18n = raw.startsWith('__i18n:');
              const resolved = isI18n
                ? intl.formatMessage({ id: raw.replace('__i18n:', '') })
                : raw;
              const isSystem = d?.reasoningType === 'system';

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
                  // Update PiP window with latest screenshot
                  if (d?.screenshot) {
                    setLatestScreenshot(d.screenshot);
                    setLatestArtifact(d.artifact || null);
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
                setMessages((prev) => {
                  const lastIdx = prev.findLastIndex(
                    (m: any) => m.role === 'reasoning',
                  );
                  if (lastIdx >= 0 && (prev[lastIdx] as any)._runId === runId) {
                    return prev.map((m, i) =>
                      i === lastIdx
                        ? {
                            ...m,
                            content:
                              m.content + '\n\n⚙️ **' + name + '**: starting...',
                            _publishId: publishId,
                          }
                        : m,
                    );
                  }
                  reasoningSectionIdRef.current++;
                  return [
                    ...prev,
                    {
                      role: 'reasoning',
                      content: '⚙️ **' + name + '**: starting...',
                      ts: Date.now(),
                      fromSSE: true,
                      _runId: runId,
                      _publishId: publishId,
                      _reasoningId: reasoningSectionIdRef.current,
                    },
                  ];
                });
                return;
              }

              if (
                subType === 'run_completed' ||
                subType === 'run_failed' ||
                subType === 'run_cancelled' ||
                subType === 'run_awaiting_user'
              ) {
                setSending(false);
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
                          ts: Date.now(),
                          fromSSE: true,
                        },
                      ];
                    });
                  }
                }

                const reply = d?.assistantReply;
                if (runId && reply) {
                  setMessages((prev) => {
                    const reasoningKeys = prev
                      .filter(
                        (m) =>
                          (m as any).role === 'reasoning' &&
                          (m as any)._reasoningId,
                      )
                      .map((m) => `reasoning-${(m as any)._reasoningId}`);
                    if (reasoningKeys.length > 0) {
                      setCollapsedKeys(
                        (k) => new Set([...k, ...reasoningKeys]),
                      );
                    }
                    // If streaming content_token already built an AI message, update in-place
                    const existingIdx = prev.findIndex(
                      (m) => (m as any).runId === runId && m.role === 'ai',
                    );
                    if (existingIdx >= 0) {
                      return prev.map((m, i) =>
                        i === existingIdx
                          ? { ...m, content: reply, _pending: false }
                          : m,
                      );
                    }
                    return [
                      ...prev,
                      {
                        role: 'ai',
                        content: reply,
                        ts: Date.now(),
                        fromSSE: true,
                      },
                    ];
                  });
                }
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

              setMessages((prev) => {
                let mutated = prev;
                if (subType === 'intent_retry') {
                  mutated = mutated.filter(
                    (m) => !(m.role === 'ai' && !(m as any).content?.trim()),
                  );
                }
                const lastIdx = mutated.findLastIndex(
                  (m: any) => m.role === 'reasoning',
                );
                if (
                  lastIdx >= 0 &&
                  (mutated[lastIdx] as any)._runId === runId
                ) {
                  const sep = isSystem ? '\n\n' : '';
                  return mutated.map((m, i) =>
                    i === lastIdx
                      ? { ...m, content: m.content + sep + resolved }
                      : m,
                  );
                }
                reasoningSectionIdRef.current++;
                return [
                  ...mutated,
                  {
                    role: 'reasoning' as const,
                    content: resolved,
                    ts: Date.now(),
                    fromSSE: true,
                    _runId: runId,
                    _reasoningId: reasoningSectionIdRef.current,
                    _publishId: publishId || undefined,
                  },
                ];
              });
              return;
            }
            if (evtType === 'page_screenshot') {
              if (d?.screenshot) {
                setLatestScreenshot(d.screenshot);
                setLatestArtifact(
                  JSON.stringify({ data: { url: d?.response || '' } }),
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
              if (rid && delta) {
                setSending(true);
                setMessages((prev) => {
                  const idx = prev.findIndex(
                    (m) => (m as any).runId === rid && m.role === 'ai',
                  );
                  if (idx >= 0) {
                    return prev.map((m, i) =>
                      i === idx ? { ...m, content: m.content + delta } : m,
                    );
                  }
                  return [
                    ...prev,
                    {
                      role: 'ai',
                      content: delta,
                      runId: rid,
                      ts: Date.now(),
                      fromSSE: true,
                      _pending: true,
                    },
                  ];
                });
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
        reasoningSectionIdRef.current = 0;
        runUserMessageRef.current = new Map();
        setTodos([]);
        setToolCalls([]);
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
                  ts: r.createdAt
                    ? new Date(r.createdAt).getTime()
                    : Date.now(),
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
                setSending(false);
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
                if (currentRunIdRef.current && currentSession?.id) {
                  agentApi.runs
                    .stop(currentSession.id, currentRunIdRef.current)
                    .catch(() => {});
                }
                currentRunIdRef.current = null;
              }}
              onCancelClarification={handleCancelClarification}
              onExpandOpen={() => {
                setExpandText(inputValue);
                setExpandOpen(true);
              }}
              sessionPanelOpen={sessionPanelOpen}
              onTogglePanel={() => setSessionPanelOpen(!sessionPanelOpen)}
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
      <PiPWindow screenshot={latestScreenshot} artifact={latestArtifact} />
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

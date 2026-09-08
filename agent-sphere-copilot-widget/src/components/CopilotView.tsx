import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { UIEvent } from 'react';
import { ApiError, createApi, stopSession } from '../api';
import type { WidgetConfig } from '../config';
import type { InstanceVO, SessionVO, UserVO } from '../types';
import { useTimelineStream } from '../useTimelineStream';
import { SendIcon, StopIcon } from '../icons';
import { WidgetTimeline } from './WidgetTimeline';

const AGENT_PAGE_SIZE = 5;
const SESSION_PAGE_SIZE = 5;
const SCROLL_END_THRESHOLD = 20;
const ACTIVE_SESSION_KEY = 'agent-sphere-widget:active-session';

interface CopilotViewProps {
  config: WidgetConfig;
  user: UserVO;
}

function mergeById<T extends { id: number }>(prev: T[], next: T[]): T[] {
  const map = new Map<number, T>();
  for (const item of prev) map.set(item.id, item);
  for (const item of next) map.set(item.id, item);
  return Array.from(map.values());
}

/** 后端返回的相对路径按 apiBase 前缀解析为绝对地址；外链原样返回。 */
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

  // 新 timeline 数据通道（REST + SSE）
  const timeline = useTimelineStream(api);

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
  // 插件下载入口
  const [pluginStoreUrl, setPluginStoreUrl] = useState<string>('');
  const [pluginDownloadUrl, setPluginDownloadUrl] = useState<string>('');
  const [pluginMenuOpen, setPluginMenuOpen] = useState(false);
  // 输入区
  const [inputText, setInputText] = useState('');
  const [sending, setSending] = useState(false);

  useEffect(() => {
    const resolveUrl = async () => {
      try {
        const res = await api.publicConfig(['plugin.download-url', 'plugin.store-url']);
        setPluginDownloadUrl(config.pluginDownloadUrl || resolveAbsoluteUrl(res?.['plugin.download-url'] || '', apiBase));
        setPluginStoreUrl(resolveAbsoluteUrl(res?.['plugin.store-url'] || '', apiBase));
      } catch {
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

  const agentListRef = useRef<HTMLDivElement>(null);
  const sessionListRef = useRef<HTMLDivElement>(null);
  const chatBodyRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

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

  // 列表内容不足一屏时自动补下一页
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

  // timeline 通道与会话绑定（切换会话即重建）
  useEffect(() => {
    if (selectedSessionId === null) {
      return;
    }
    timeline.connect(selectedSessionId, user.token, apiBase);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedSessionId, user.token, apiBase]);

  // 进入会话先滚到底一次；随后流式/刚发送时保持钉底（翻旧页 prepend 不动）
  const initialScrollDoneRef = useRef(false);
  useEffect(() => {
    initialScrollDoneRef.current = false;
  }, [selectedSessionId]);

  useEffect(() => {
    const el = chatBodyRef.current;
    if (!el) return;
    const lastRow = timeline.rows[timeline.rows.length - 1];
    const hasPending = timeline.pendingUserRows.length > 0;
    if (!initialScrollDoneRef.current && (timeline.rows.length > 0 || hasPending)) {
      initialScrollDoneRef.current = true;
      el.scrollTop = el.scrollHeight;
      return;
    }
    if (hasPending) {
      el.scrollTop = el.scrollHeight;
      return;
    }
    if (lastRow && (lastRow.state === 'RUNNING' || lastRow.kind === 'user')) {
      el.scrollTop = el.scrollHeight;
    }
  }, [selectedSessionId, timeline.rows, timeline.pendingUserRows]);

  const respondTimelineClarify = useCallback(
    (runId: number, clarificationId: string, response: string) => {
      if (selectedSessionId === null) {
        return;
      }
      void api
        .clarify(selectedSessionId, runId, response, clarificationId)
        .catch((err) => setError((err as ApiError).message));
    },
    [api, selectedSessionId],
  );

  const handleSend = async () => {
    const text = inputText.trim();
    if (!text || inputLocked || selectedSessionId === null) {
      return;
    }
    setSending(true);
    setInputText('');
    timeline.addUserMessage(text);
    try {
      await api.sendMessage(selectedSessionId, text);
      // POST 成功立即补拉权威行（用户行 + assistant 容器 + run 状态），无需等终态事件
      void timeline.refreshLatest(selectedSessionId);
    } catch (err) {
      timeline.removeUserMessage(text);
      timeline.markRunInactive();
      setError((err as ApiError).message);
    } finally {
      setSending(false);
    }
  };

  const handleStop = async () => {
    if (selectedSessionId !== null) {
      try {
        await stopSession(apiBase, selectedSessionId);
      } catch (err) {
        setError((err as ApiError).message);
      }
    }
  };

  const handleChatBodyScroll = (event: UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    // 靠近顶部才加载更早消息；滚到底部时不能触发加载（否则会翻出全量）
    if (
      el.scrollTop < SCROLL_END_THRESHOLD &&
      timeline.hasMore &&
      !timeline.loadingOlder
    ) {
      if (selectedSessionId != null) {
        void timeline.loadOlder(selectedSessionId);
      }
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
  // 输入锁定：发送中 / run 进行中（事件驱动）/ 用户消息刚上墙未替换。
  // runActive 由 useTimelineStream 事件驱动：点击发送（addUserMessage）即 true，
  // 终态 SSE（完成/失败/取消/等待澄清）为 false —— 不再扫描 rows，避免历史残留行干扰。
  const runActive = timeline.runActive;
  const inputLocked =
    sending || runActive || timeline.pendingUserRows.length > 0;

  // 解锁后把焦点还给输入框，方便连发
  useEffect(() => {
    if (!inputLocked && selectedSessionId != null) {
      inputRef.current?.focus();
    }
  }, [inputLocked, selectedSessionId]);

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
              onClick={(e: React.MouseEvent<HTMLAnchorElement>) => {
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
            <div
              ref={chatBodyRef}
              className="aw-chat-body"
              onScroll={handleChatBodyScroll}
            >
              <WidgetTimeline
                rows={timeline.rows}
                hasMore={timeline.hasMore}
                loadingOlder={timeline.loadingOlder}
                onLoadOlder={() => {
                  if (selectedSessionId != null) {
                    void timeline.loadOlder(selectedSessionId);
                  }
                }}
                pendingUserRows={timeline.pendingUserRows}
                subAgentLiveMap={timeline.subAgentLiveMap}
                loadSubAgentSteps={timeline.loadSubAgentSteps}
                loadFile={(fileKey) => api.loadFile(fileKey)}
                onRespondClarify={respondTimelineClarify}
              />
            </div>
            <div className="aw-chat-input">
              {runActive ? (
                <div className="aw-tl-divider">
                  <span className="aw-running-dot" />
                  Agent 运行中…
                </div>
              ) : null}
              <div className="aw-chat-input-row">
                <textarea
                  ref={inputRef}
                  className="aw-chat-textarea"
                  rows={1}
                  value={inputText}
                  disabled={inputLocked}
                  placeholder={inputLocked ? '回复中…' : '输入消息…（Enter 发送，Shift+Enter 换行）'}
                  onChange={(e) => setInputText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey && inputText.trim()) {
                      e.preventDefault();
                      void handleSend();
                    }
                  }}
                />
                {runActive ? (
                  <button
                    type="button"
                    className="aw-chat-stop"
                    title="停止回复"
                    aria-label="停止回复"
                    onClick={() => void handleStop()}
                  >
                    <StopIcon size={16} />
                  </button>
                ) : (
                  <button
                    type="button"
                    className="aw-chat-send"
                    title="发送"
                    aria-label="发送"
                    disabled={!inputText.trim() || inputLocked}
                    onClick={() => void handleSend()}
                  >
                    <SendIcon size={16} />
                  </button>
                )}
              </div>
            </div>
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

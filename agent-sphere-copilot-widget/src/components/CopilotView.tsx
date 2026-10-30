import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { UIEvent } from 'react';
import { ApiError, createApi, stopSession } from '../api';
import { clearFileCache } from '../fileCache';
import { allowsWidgetSession, businessTypeOf } from '../instance';
import type { WidgetConfig } from '../config';
import type { InstanceVO, SessionVO, UsageData, UserVO } from '../types';
import { useTimelineStream } from '../useTimelineStream';
import { PictureIcon, SendIcon, StopIcon } from '../icons';
import { WidgetTimeline } from './WidgetTimeline';
import { WidgetClarificationDrawer } from './WidgetClarificationDrawer';
import { WidgetToolApprovalDrawer } from './WidgetToolApprovalDrawer';
import { cacheHitRate, formatTokens } from './Usage';

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
  // 稳定引用：避免每次渲染都新建函数导致 UserImages effect 反复触发
  const loadFile = useCallback((fileKey: string) => api.loadFile(fileKey), [api]);

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
  const [approvalSubmitting, setApprovalSubmitting] = useState(false);
  const [clarificationSubmitting, setClarificationSubmitting] = useState(false);
  const [inputHeight, setInputHeight] = useState(0);
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
  const [attachment, setAttachment] = useState<{
    fileKey: string;
    contentType: string;
    previewUrl: string;
  } | null>(null);
  const [uploadingAttachment, setUploadingAttachment] = useState(false);
  const [sessionUsage, setSessionUsage] = useState<UsageData | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const attachmentRef = useRef(attachment);
  attachmentRef.current = attachment;

  /** 释放当前附件预览 objectURL 并清空附件（发送 / 移除 / 替换时调用）。 */
  const discardAttachment = useCallback(() => {
    setAttachment((prev) => {
      if (prev?.previewUrl) {
        try {
          URL.revokeObjectURL(prev.previewUrl);
        } catch {
          /* 忽略 */
        }
      }
      return null;
    });
  }, []);

  // 组件卸载：释放附件预览 objectURL 与文件 objectURL 缓存
  useEffect(
    () => () => {
      const previewUrl = attachmentRef.current?.previewUrl;
      if (previewUrl) {
        try {
          URL.revokeObjectURL(previewUrl);
        } catch {
          /* 忽略 */
        }
      }
      clearFileCache();
    },
    [],
  );

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
  const chatInputRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = chatInputRef.current;
    if (!el) {
      setInputHeight(0);
      return;
    }
    const update = () => setInputHeight(el.offsetHeight);
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [selectedSessionId, sending, inputText, attachment]);
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
    // 实例未开放 widget 端会话时不允许手动新建：按钮已置灰，这里再守一道 ——
    // 按钮置灰只是 UI，函数本身应当自守，避免样式/结构变更后绕过。
    const agent = instances.find((i) => i.id === selectedAgentId);
    if (!allowsWidgetSession(agent)) {
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
    // 切会话：释放上一会话的图片 objectURL 缓存
    clearFileCache();
    timeline.connect(selectedSessionId, user.token, apiBase);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedSessionId, user.token, apiBase]);

  // 会话级用量：run 结束或切换会话时刷新（吸底条）
  useEffect(() => {
    if (selectedSessionId === null) {
      setSessionUsage(null);
      return;
    }
    if (timeline.runActive) return;
    let alive = true;
    api
      .sessionUsage(selectedSessionId)
      .then((u) => {
        if (alive) setSessionUsage(u);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedSessionId, timeline.runActive, api]);

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
      // 乐观登记已应答，避免随后 refreshLatest 用服务端旧 PENDING 回退。
      // 必须带 clarificationId：同 run 续跑复用 runId，只按 runId 会误伤后续澄清。
      timeline.markClarificationAnswered(runId, clarificationId);
      void api
        .clarify(selectedSessionId, runId, response, clarificationId)
        .catch((err) => setError((err as ApiError).message));
    },
    [api, selectedSessionId, timeline],
  );

  const handlePickImage = () => {
    if (attachment || uploadingAttachment || sending || inputLocked) return;
    fileInputRef.current?.click();
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (!/^image\/(jpeg|png|webp|gif)$/i.test(file.type)) {
      setError('仅支持 jpeg/png/webp/gif 图片');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      setError('图片不能超过 5MB');
      return;
    }
    try {
      setUploadingAttachment(true);
      const res = await api.uploadFile(file);
      discardAttachment();
      setAttachment({
        fileKey: res.fileKey,
        contentType: res.contentType,
        previewUrl: URL.createObjectURL(file),
      });
    } catch (err) {
      setError((err as ApiError).message);
    } finally {
      setUploadingAttachment(false);
    }
  };

  const handleSend = async () => {
    const text = inputText.trim();
    const keys = attachment ? [attachment.fileKey] : undefined;
    const hasAttachment = !!attachment;
    if ((!text && !hasAttachment) || inputLocked || selectedSessionId === null) {
      return;
    }
    setSending(true);
    setInputText('');
    const discText = text || '[图片]';
    timeline.addUserMessage(discText, attachment
      ? [{ fileKey: attachment.fileKey, contentType: attachment.contentType }]
      : undefined);
    const sentKeys = keys;
    const sentText = text;
    discardAttachment();
    try {
      await api.sendMessage(selectedSessionId, sentText, sentKeys);
      // POST 成功立即补拉权威行（用户行 + assistant 容器 + run 状态），无需等终态事件
      void timeline.refreshLatest(selectedSessionId);
    } catch (err) {
      timeline.removeUserMessage(discText);
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
        return;
      }
      // 兜底：SSE 终态若丢失，runActive 会一直锁住输入/停止按钮。8s 后本地复位
      // （权威 run_status 行到达时会自然覆盖）。与主站 UI 的兜底一致。
      window.setTimeout(() => timeline.markRunInactive(), 8000);
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
  /**
   * 实例是否**未开放** widget 端会话（`agent_instance.allow_widget_session` 为假）。
   *
   * <p>关掉时 widget 里既不能手动发起会话，也不能手动对话 —— 只保留「查看历史会话」的能力。
   * 服务端在 `createSession` / `chat` 用同一规则强校验，这里只是让 UI 保持一致。
   *
   * <p>按**实例**统一门控而非按会话判定：即使选中的是该实例下的历史聊天会话，也一样隐藏输入区。
   * 规则只依赖 instance，无需额外请求（开关已随实例列表返回）。
   */
  const businessType = businessTypeOf(selectedAgent);
  const widgetSessionBlocked = !allowsWidgetSession(selectedAgent);
  const visibleSessions = selectedAgentId
    ? sessions.filter((s) => s.agentInstanceId === selectedAgentId)
    : [];
  // 输入锁定：发送中 / run 进行中（事件驱动）/ 用户消息刚上墙未替换。
  // runActive 由 useTimelineStream 事件驱动：点击发送（addUserMessage）即 true，
  // 终态 SSE（完成/失败/取消/等待澄清）为 false —— 不再扫描 rows，避免历史残留行干扰。
  const runActive = timeline.runActive;
  const inputLocked =
    sending ||
    runActive ||
    timeline.pendingUserRows.length > 0 ||
    timeline.toolApproval != null ||
    timeline.clarification != null;

  // 解锁后把焦点还给输入框，方便连发
  useEffect(() => {
    if (!inputLocked && selectedSessionId != null) {
      inputRef.current?.focus();
    }
  }, [inputLocked, selectedSessionId]);

  const handleResolveToolApproval = async (
    decisions: Array<{ callId: string; decision: string }>,
  ) => {
    setApprovalSubmitting(true);
    try {
      await timeline.resolveToolApproval(decisions);
    } finally {
      setApprovalSubmitting(false);
    }
  };

  const handleResolveClarifications = async (
    answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
  ) => {
    setClarificationSubmitting(true);
    try {
      await timeline.resolveClarifications(answers);
    } finally {
      setClarificationSubmitting(false);
    }
  };

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
        {/* 原生 title 在 disabled 元素上不触发（浏览器不对禁用表单控件派发鼠标事件），
            所以提示挂在包裹层上，按钮本身仍保留真实 disabled 语义。 */}
        <span
          className="aw-new-session-wrap"
          title={
            widgetSessionBlocked
              ? businessType
                ? `「${businessType}」不支持手动发起会话；如需聊天机器人请申请开通`
                : '该实例不支持手动发起会话；如需聊天机器人请申请开通'
              : undefined
          }
        >
          <button
            type="button"
            className="aw-new-session"
            disabled={widgetSessionBlocked}
            onClick={() => void handleCreateSession()}
          >
            新会话
          </button>
        </span>
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
                loadFile={loadFile}
                onRespondClarify={respondTimelineClarify}
                onCancelClarify={() => {
                  void handleStop();
                }}
                preparingTool={timeline.preparingTool}
              />
              {sessionUsage && Number(sessionUsage.totalTokens) > 0 ? (
                <div className="aw-session-usage">
                  ♨ 会话用量 · {formatTokens(sessionUsage.totalTokens)} tokens
                  {sessionUsage.promptTokens
                    ? ` · prompt ${formatTokens(sessionUsage.promptTokens)}`
                    : ''}
                  {cacheHitRate(sessionUsage) != null
                    ? ` · cache ${cacheHitRate(sessionUsage)}%`
                    : ''}
                </div>
              ) : null}
            </div>
            {widgetSessionBlocked ? (
              <div className="aw-business-autochat">
                <div className="aw-business-autochat-label">--{businessType}自动会话--</div>
                <div className="aw-business-autochat-hint">如需聊天机器人请申请开通</div>
              </div>
            ) : (
            <div ref={chatInputRef} className="aw-chat-input">
              {runActive ? (
                <div className="aw-tl-divider">
                  <span className="aw-running-dot" />
                  Agent 运行中…
                </div>
              ) : null}
              {attachment ? (
                <div className="aw-attach-preview">
                  <img src={attachment.previewUrl} alt="attachment" className="aw-attach-preview-img" />
                  <button
                    type="button"
                    className="aw-attach-remove"
                    title="移除图片"
                    aria-label="移除图片"
                    onClick={discardAttachment}
                  >
                    ✕
                  </button>
                </div>
              ) : null}
              <input
                ref={fileInputRef}
                type="file"
                accept="image/png,image/jpeg,image/webp,image/gif"
                style={{ display: 'none' }}
                onChange={handleFileChange}
              />
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
                    if (
                      e.key === 'Enter' &&
                      !e.shiftKey &&
                      (inputText.trim() || attachment)
                    ) {
                      e.preventDefault();
                      void handleSend();
                    }
                  }}
                />
                <button
                  type="button"
                  className="aw-chat-attach"
                  title="添加图片"
                  aria-label="添加图片"
                  disabled={!!attachment || uploadingAttachment || sending || inputLocked}
                  onClick={handlePickImage}
                >
                  <PictureIcon size={16} />
                </button>
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
                    disabled={
                      (!inputText.trim() && !attachment) || inputLocked
                    }
                    onClick={() => void handleSend()}
                  >
                    <SendIcon size={16} />
                  </button>
                )}
              </div>
            </div>
            )}
            <WidgetToolApprovalDrawer
              batch={timeline.toolApproval}
              submitting={approvalSubmitting}
              bottomOffset={inputHeight}
              onResolve={handleResolveToolApproval}
            />
            <WidgetClarificationDrawer
              batch={timeline.clarification}
              submitting={clarificationSubmitting}
              bottomOffset={inputHeight}
              onResolve={handleResolveClarifications}
              onCancel={() => void handleStop()}
            />
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

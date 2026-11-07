import { RUN_STATE } from '../constants';
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
const CHAT_STICK_THRESHOLD = 160;
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

/** Resolve a relative path returned by the backend against the apiBase prefix; external links are returned unchanged. */
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
  // Stable reference: avoid creating a new function on every render, which would re-trigger the UserImages effect
  const loadFile = useCallback((fileKey: string) => api.loadFile(fileKey), [api]);

  // New timeline data channel (REST + SSE)
  const timeline = useTimelineStream(api);

  // Instances (paged)
  const [instances, setInstances] = useState<InstanceVO[]>([]);
  const [agentPage, setAgentPage] = useState(1);
  const [hasMoreAgents, setHasMoreAgents] = useState(false);
  const [loadingAgents, setLoadingAgents] = useState(false);
  // Sessions (paged)
  const [sessions, setSessions] = useState<SessionVO[]>([]);
  const [sessionOffset, setSessionOffset] = useState(0);
  const [hasMoreSessions, setHasMoreSessions] = useState(false);
  const [loadingSessions, setLoadingSessions] = useState(false);
  // Selection
  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null);
  const [approvalSubmitting, setApprovalSubmitting] = useState(false);
  const [clarificationSubmitting, setClarificationSubmitting] = useState(false);
  const [inputHeight, setInputHeight] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Session rename
  const [editingSessionId, setEditingSessionId] = useState<number | null>(null);
  const [editingTitle, setEditingTitle] = useState('');
  // Archive confirmation
  const [confirmingArchiveId, setConfirmingArchiveId] = useState<number | null>(null);
  // Plugin download entry
  const [pluginStoreUrl, setPluginStoreUrl] = useState<string>('');
  const [pluginDownloadUrl, setPluginDownloadUrl] = useState<string>('');
  const [pluginMenuOpen, setPluginMenuOpen] = useState(false);
  // Input area
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

  /** Release the current attachment preview objectURL and clear the attachment (called on send / remove / replace). */
  const discardAttachment = useCallback(() => {
    setAttachment((prev) => {
      if (prev?.previewUrl) {
        try {
          URL.revokeObjectURL(prev.previewUrl);
        } catch {
          /* ignore */
        }
      }
      return null;
    });
  }, []);

  // On unmount: release the attachment preview objectURL and the file objectURL cache
  useEffect(
    () => () => {
      const previewUrl = attachmentRef.current?.previewUrl;
      if (previewUrl) {
        try {
          URL.revokeObjectURL(previewUrl);
        } catch {
          /* ignore */
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
  const chatContentRef = useRef<HTMLDivElement>(null);
  const chatInputRef = useRef<HTMLDivElement>(null);
  const chatStickRef = useRef(true);
  const chatProgrammaticScrollRef = useRef(false);
  const chatLastScrollHeightRef = useRef(0);
  const chatLastScrollTopRef = useRef(0);

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

  // Automatically load the next page while the list does not fill the viewport
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

  // Automatically load the next page while the session list does not fill the viewport
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
    // Manual session creation is not allowed when the instance has widget sessions disabled: the button is greyed out, but guard here as well -
    // a greyed-out button is UI only; the function itself should self-guard so a style/structure change cannot bypass it.
    const agent = instances.find((i) => i.id === selectedAgentId);
    if (!allowsWidgetSession(agent)) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const created = await api.createSession(selectedAgentId, 'New session');
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

  // Notify chrome-extension of the current session (sessionStorage bridge + immediate event)
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

  // Bind the timeline channel to the session (rebuilt on session switch)
  useEffect(() => {
    if (selectedSessionId === null) {
      return;
    }
    // Session switch: release the previous session's image objectURL cache
    clearFileCache();
    timeline.connect(selectedSessionId, user.token, apiBase);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedSessionId, user.token, apiBase]);

  // Session-level usage: refresh when the run ends or the session switches (sticky footer)
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

  // Scroll to the bottom once when entering a session; afterwards any content growth stays pinned to the bottom (prepending older pages does not move it)
  const initialScrollDoneRef = useRef(false);
  useEffect(() => {
    initialScrollDoneRef.current = false;
    chatStickRef.current = true;
  }, [selectedSessionId]);

  const scrollChatToBottom = useCallback((force = false) => {
    const el = chatBodyRef.current;
    if (!el) return;
    if (!force && !chatStickRef.current) return;
    chatProgrammaticScrollRef.current = true;
    el.scrollTop = el.scrollHeight;
    chatLastScrollHeightRef.current = el.scrollHeight;
    chatLastScrollTopRef.current = el.scrollTop;
    requestAnimationFrame(() => {
      chatProgrammaticScrollRef.current = false;
    });
  }, []);

  // Async content such as long messages, todos and screenshots changes the content height; observe the content layer rather than the scroll layer.
  useEffect(() => {
    const el = chatBodyRef.current;
    const inner = chatContentRef.current;
    if (!el || !inner) return;
    chatLastScrollHeightRef.current = el.scrollHeight;
    chatLastScrollTopRef.current = el.scrollTop;
    const ro = new ResizeObserver(() => {
      chatLastScrollHeightRef.current = el.scrollHeight;
      scrollChatToBottom();
    });
    ro.observe(inner);
    return () => ro.disconnect();
  }, [selectedSessionId, scrollChatToBottom]);

  const lastTimelineRow = timeline.rows[timeline.rows.length - 1];
  const lastTimelineKey = [
    timeline.rows.length,
    lastTimelineRow?.seq ?? '',
    lastTimelineRow?.state ?? '',
    timeline.pendingUserRows.length,
  ].join(':');
  useEffect(() => {
    const el = chatBodyRef.current;
    if (!el) return;
    const hasPending = timeline.pendingUserRows.length > 0;
    if (
      !initialScrollDoneRef.current &&
      (timeline.rows.length > 0 || hasPending)
    ) {
      initialScrollDoneRef.current = true;
      const raf = requestAnimationFrame(() => scrollChatToBottom(true));
      return () => cancelAnimationFrame(raf);
    }
    if (!chatStickRef.current) return;
    const raf = requestAnimationFrame(() => scrollChatToBottom());
    return () => cancelAnimationFrame(raf);
  }, [selectedSessionId, lastTimelineKey, scrollChatToBottom]);

  const respondTimelineClarify = useCallback(
    (runId: number, clarificationId: string, response: string) => {
      if (selectedSessionId === null) {
        return;
      }
      // Record it as answered optimistically, so the following refreshLatest does not roll back with a stale server PENDING.
      // clarificationId is mandatory: continuing the same run reuses runId, and runId alone would affect later clarifications.
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
      setError('Only jpeg/png/webp/gif images are supported');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      setError('Image must be 5MB or smaller');
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
    const discText = text || '[Image]';
    timeline.addUserMessage(discText, attachment
      ? [{ fileKey: attachment.fileKey, contentType: attachment.contentType }]
      : undefined);
    const sentKeys = keys;
    const sentText = text;
    discardAttachment();
    try {
      await api.sendMessage(selectedSessionId, sentText, sentKeys);
      // On POST success, immediately catch up the authoritative rows (user row + assistant container + run status); no need to wait for a terminal event
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
      // Fallback: if the SSE terminal event is lost, runActive would keep the input/stop button locked. Reset locally after 8s
      // (the authoritative run_status row will naturally override it). Same fallback as the main site UI.
      window.setTimeout(() => timeline.markRunInactive(), 8000);
    }
  };

  const handleChatBodyScroll = (event: UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    if (!chatProgrammaticScrollRef.current) {
      const height = el.scrollHeight;
      const scrollTop = el.scrollTop;
      const grew = height > chatLastScrollHeightRef.current + 1;
      const userScrolledUp = scrollTop < chatLastScrollTopRef.current - 1;
      chatLastScrollHeightRef.current = height;
      chatLastScrollTopRef.current = scrollTop;
      const distance = height - scrollTop - el.clientHeight;
      // A scroll caused by content growth does not mean the user left the bottom
      if (!(grew && chatStickRef.current && !userScrolledUp)) {
        chatStickRef.current = distance < CHAT_STICK_THRESHOLD;
      }
    }
    // Load older messages only when near the top; must not trigger while at the bottom (otherwise it would page in everything)
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
   * Whether the instance has widget sessions **disabled** (`agent_instance.allow_widget_session` is false).
   *
   * <p>When off, the widget allows neither manual session creation nor manual chatting - only "view historical sessions" remains.
   * The server enforces the same rule in `createSession` / `chat`; this merely keeps the UI consistent.
   *
   * <p>Gating is per **instance**, not per session: even when the selected session is a historical chat session under that instance, the input area is hidden.
   * The rule only depends on instance and needs no extra request (the switch comes with the instance list).
   */
  const businessType = businessTypeOf(selectedAgent);
  const widgetSessionBlocked = !allowsWidgetSession(selectedAgent);
  const visibleSessions = selectedAgentId
    ? sessions.filter((s) => s.agentInstanceId === selectedAgentId)
    : [];
  // Input lock: sending / user message just posted but not replaced / runtime endpoint (aggregated from source tables).
  // The timeline only renders; it does not participate in lock decisions.
  const runActive = timeline.runActive;
  const inputLocked =
    sending ||
    timeline.pendingUserRows.length > 0 ||
    (timeline.runtimeState ? !timeline.runtimeState.canSend : runActive);

  // Return focus to the input after unlocking, for consecutive sends
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
        <div className="aw-sidebar-title">Instances</div>
        <div ref={agentListRef} className="aw-list aw-agent-list" onScroll={handleAgentScroll}>
          {instances.length === 0 && !loadingAgents ? (
            <div className="aw-item-hint">No instances</div>
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
          {loadingAgents ? <div className="aw-item-hint">Loading…</div> : null}
        </div>
        <div className="aw-sidebar-title">Sessions</div>
        <div ref={sessionListRef} className="aw-list aw-session-list" onScroll={handleSessionScroll}>
          {visibleSessions.length === 0 && !loadingSessions ? (
            <div className="aw-item-hint">No sessions yet. Create one.</div>
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
                  title="Save"
                  role="button"
                  aria-label="Save rename"
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
                  title="Cancel"
                  role="button"
                  aria-label="Cancel rename"
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
                    <span className="aw-archive-hint">Archive?</span>
                    <span
                      className="aw-item-edit aw-item-ok"
                      title="Confirm archive"
                      role="button"
                      aria-label="Confirm archive"
                      onClick={(e) => {
                        e.stopPropagation();
                        void handleCloseSession(s);
                      }}
                    >
                      ✓
                    </span>
                    <span
                      className="aw-item-edit aw-item-cancel"
                      title="Cancel"
                      role="button"
                      aria-label="Cancel archive"
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
                      title="Rename"
                      role="button"
                      aria-label="Rename session"
                      onClick={(e) => {
                        e.stopPropagation();
                        startRename(s);
                      }}
                    >
                      ✎
                    </span>
                    <span
                      className="aw-item-edit aw-item-archive"
                      title="Archive"
                      role="button"
                      aria-label="Archive session"
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
          {loadingSessions ? <div className="aw-item-hint">Loading…</div> : null}
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
                    Download from marketplace
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
                    In-app download
                  </a>
                ) : null}
              </div>
            ) : null}
            <a
              className="aw-plugin-download"
              href={pluginDownloadUrl || pluginStoreUrl || '#'}
              target="_blank"
              rel="noreferrer"
              title="Download Chrome extension"
              onClick={(e: React.MouseEvent<HTMLAnchorElement>) => {
                e.preventDefault();
                e.stopPropagation();
                setPluginMenuOpen((open) => !open);
              }}
            >
              Extension download
              <span className="aw-plugin-caret">▾</span>
            </a>
          </div>
        ) : null}
        {/* A native title does not fire on disabled elements (the browser dispatches no mouse events to disabled form controls),
            so the tooltip lives on the wrapper while the button keeps its real disabled semantics. */}
        <span
          className="aw-new-session-wrap"
          title={
            widgetSessionBlocked
              ? businessType
                ? `"${businessType}" does not support starting sessions manually. Request access to enable the chatbot.`
                : 'This instance does not support starting sessions manually. Request access to enable the chatbot.'
              : undefined
          }
        >
          <button
            type="button"
            className="aw-new-session"
            disabled={widgetSessionBlocked}
            onClick={() => void handleCreateSession()}
          >
            New session
          </button>
        </span>
      </div>

      <div className="aw-main">
        <div className="aw-header">
          <span className="aw-title">{selectedAgent?.name ?? 'Select an instance'}</span>
        </div>

        {error ? <div className="aw-error">{error}</div> : null}
        {loading ? <div className="aw-loading">Loading…</div> : null}

        {selectedSessionId !== null && selectedSession && selectedAgentId !== null ? (
          <div className="aw-chat">
            <div
              ref={chatBodyRef}
              className="aw-chat-body"
              onScroll={handleChatBodyScroll}
            >
              <div ref={chatContentRef}>
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
                  retryState={
                    timeline.runtimeState?.runStatus === RUN_STATE.WAITING &&
                    timeline.runtimeState?.waitKind === 'LLM_RETRY' &&
                    timeline.runtimeState?.runId != null
                      ? {
                          runId: Number(timeline.runtimeState.runId),
                          attempt: timeline.runtimeState.retryAttempt,
                          max: timeline.runtimeState.retryMax,
                          remainingMs: Number(
                            timeline.runtimeState.retryRemainingMs || 0,
                          ),
                        }
                      : null
                  }
                />
                {sessionUsage && Number(sessionUsage.totalTokens) > 0 ? (
                  <div className="aw-session-usage">
                    ♨ Session usage · {formatTokens(sessionUsage.totalTokens)} tokens
                    {sessionUsage.promptTokens
                      ? ` · prompt ${formatTokens(sessionUsage.promptTokens)}`
                      : ''}
                    {cacheHitRate(sessionUsage) != null
                      ? ` · cache ${cacheHitRate(sessionUsage)}%`
                      : ''}
                  </div>
                ) : null}
              </div>
            </div>
            {widgetSessionBlocked ? (
              <div className="aw-business-autochat">
                <div className="aw-business-autochat-label">--{businessType} auto session--</div>
                <div className="aw-business-autochat-hint">Request access to enable the chatbot.</div>
              </div>
            ) : (
            <div ref={chatInputRef} className="aw-chat-input">
              {runActive ? (
                <div className="aw-tl-divider">
                  <span className="aw-running-dot" />
                  Agent is running…
                </div>
              ) : null}
              {attachment ? (
                <div className="aw-attach-preview">
                  <img src={attachment.previewUrl} alt="attachment" className="aw-attach-preview-img" />
                  <button
                    type="button"
                    className="aw-attach-remove"
                    title="Remove image"
                    aria-label="Remove image"
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
                  placeholder={inputLocked ? 'Replying…' : 'Type a message… (Enter to send, Shift+Enter for a new line)'}
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
                  title="Add image"
                  aria-label="Add image"
                  disabled={!!attachment || uploadingAttachment || sending || inputLocked}
                  onClick={handlePickImage}
                >
                  <PictureIcon size={16} />
                </button>
                {runActive ? (
                  <button
                    type="button"
                    className="aw-chat-stop"
                    title="Stop replying"
                    aria-label="Stop replying"
                    onClick={() => void handleStop()}
                  >
                    <StopIcon size={16} />
                  </button>
                ) : (
                  <button
                    type="button"
                    className="aw-chat-send"
                    title="Send"
                    aria-label="Send"
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
            {!loading ? 'Select or create a session to start chatting' : ''}
          </div>
        )}
      </div>
    </div>
  );
}

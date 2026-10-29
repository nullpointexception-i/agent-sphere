import { useIntl } from '@umijs/max';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useStyles } from '../../style';
import { cacheHitRate, contextUsagePercent, formatTokens } from '../Usage';
import Footer from './Footer';
import Header from './Header';
import type { SubAgentLiveMap, SubAgentTimelineItem } from './subAgentTypes';
import TimelineList from './TimelineList';

const USAGE_SCROLL_SPEED_PX_PER_MS = 0.03;

interface ChatMainProps {
  currentSession: any;
  instances: any[];
  selectedModelRouteId?: number;
  onModelRouteChange: (id: number | undefined) => void;
  modelRoutes: any[];
  sseConnected: boolean;
  messages: any[];
  /** 统一 Timeline 打平行（替代旧多源拼装渲染）。 */
  timeline: any[];
  timelineHasMore: boolean;
  onLoadOlderTimeline: () => void;
  onRespondClarify: (row: any, response: string) => void;
  loadSubAgentSteps: (id: number) => Promise<SubAgentTimelineItem[]>;
  /** 子 Agent 实时步骤（SSE 纯驱动，按 subAgentRunId 聚合）。 */
  subAgentLive?: SubAgentLiveMap;
  hasMoreHistory: boolean;
  onLoadMoreHistory: () => void;
  inputValue: string;
  onInputValueChange: (v: string) => void;
  sending: boolean;
  onSendMessage: () => void;
  onCancelSend: () => void;
  onCancelClarification?: (clarification: any) => void;
  onExpandOpen: () => void;
  sessionPanelOpen: boolean;
  onTogglePanel: () => void;
  /** 会话级用量聚合（聊天区最下方吸底悬浮展示）。 */
  sessionUsage?: any;
}

export default function ChatMain({
  currentSession,
  instances,
  selectedModelRouteId,
  onModelRouteChange,
  modelRoutes,
  sseConnected,
  messages,
  timeline,
  timelineHasMore,
  onLoadOlderTimeline,
  onRespondClarify,
  onCancelClarification,
  loadSubAgentSteps,
  subAgentLive,
  inputValue,
  onInputValueChange,
  sending,
  onSendMessage,
  onCancelSend,
  onExpandOpen,
  sessionPanelOpen,
  onTogglePanel,
  sessionUsage,
}: ChatMainProps) {
  const sessionKey = currentSession?.id || '';
  const intl = useIntl();
  const { styles } = useStyles();
  const currentRoute = modelRoutes.find(
    (r: any) => r.id === selectedModelRouteId,
  );
  const contextPercent = contextUsagePercent(
    sessionUsage?.latestPromptTokens,
    currentRoute?.maxInputTokens,
  );
  const sessionCacheRate = cacheHitRate(sessionUsage);
  const usageTitleText = intl.formatMessage({
    id: 'pages.chat.sessionUsage.title',
    defaultMessage: 'Session total',
  });
  const usageAllModelsText = intl.formatMessage({
    id: 'pages.chat.sessionUsage.allModels',
    defaultMessage: 'All model calls',
  });
  const usagePromptLabel = intl.formatMessage({
    id: 'pages.chat.sessionUsage.prompt',
    defaultMessage: 'prompt',
  });
  const usageCompletionLabel = intl.formatMessage({
    id: 'pages.chat.sessionUsage.completion',
    defaultMessage: 'completion',
  });
  const usageCacheLabel = intl.formatMessage({
    id: 'pages.chat.sessionUsage.cache',
    defaultMessage: 'cache',
  });
  const usageContextLabel = intl.formatMessage({
    id: 'pages.chat.sessionUsage.context',
    defaultMessage: 'current context',
  });
  const usageTotalText = formatTokens(sessionUsage?.totalTokens);
  const usagePromptText = formatTokens(sessionUsage?.promptTokens);
  const usageCompletionText = formatTokens(sessionUsage?.completionTokens);
  const usageBaseText = `♨ ${usageTitleText} · ${usageAllModelsText} · ${usageTotalText} tokens`;
  const usagePromptPart =
    usagePromptText !== '0' ? ` · ${usagePromptLabel} ${usagePromptText}` : '';
  const usageCompletionPart =
    usageCompletionText !== '0'
      ? ` · ${usageCompletionLabel} ${usageCompletionText}`
      : '';
  const usageCachePart =
    sessionCacheRate != null ? ` · ${usageCacheLabel} ${sessionCacheRate}%` : '';
  const usageContextPart =
    contextPercent != null ? ` · ${usageContextLabel} ${contextPercent}%` : '';
  const usageFullText = `${usageBaseText}${usagePromptPart}${usageCompletionPart}${usageCachePart}${usageContextPart}`;
  const messagesRef = useRef<HTMLDivElement>(null);
  const timelineWrapRef = useRef<HTMLDivElement>(null);
  const usageBarRef = useRef<HTMLDivElement>(null);
  const usageScrollRef = useRef<HTMLDivElement>(null);
  const usageFullMeasureRef = useRef<HTMLSpanElement>(null);
  const usagePausedRef = useRef(false);
  const [usageCompact, setUsageCompact] = useState(false);
  const [usageHovered, setUsageHovered] = useState(false);
  const showPromptCompletion = !usageCompact || usageHovered;

  const hasMessages =
    messages.some((m: any) => m.content && m.content !== '{}') ||
    timeline.length > 0;

  // 单一滚动容器（.messages）接手消息滚动：
  // - stickRef 在 scroll 事件持续记录「用户是否在底部附近」（增长前的位置，解决大块增长 >160px 不自滚）；
  // - ResizeObserver 观察内容包裹层，任何内容高度变化（子 Agent 展开/异步步骤/SSE 流式/工具块展开/图片加载/大块合并）
  //   都在「用户原本在底部」时滚到底，不打断向上回看 / 加载更早。
  const stickRef = useRef(true);
  useEffect(() => {
    const el = messagesRef.current;
    const inner = timelineWrapRef.current;
    if (!el || !inner) return;
    const maybeStick = () => {
      if (stickRef.current) el.scrollTop = el.scrollHeight;
    };
    const onScroll = () => {
      stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 160;
    };
    onScroll();
    el.addEventListener('scroll', onScroll, { passive: true });
    const ro = new ResizeObserver(maybeStick);
    ro.observe(inner);
    maybeStick();
    return () => {
      el.removeEventListener('scroll', onScroll);
      ro.disconnect();
    };
  }, [hasMessages]);

  useLayoutEffect(() => {
    const bar = usageBarRef.current;
    const full = usageFullMeasureRef.current;
    if (!bar || !full) return;
    const measure = () => {
      const computed = window.getComputedStyle(bar);
      const padding =
        (Number.parseFloat(computed.paddingLeft) || 0) +
        (Number.parseFloat(computed.paddingRight) || 0);
      const available = Math.max(0, bar.clientWidth - padding);
      setUsageCompact(full.getBoundingClientRect().width > available + 0.5);
    };
    measure();
    let frame = 0;
    const schedule = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(measure);
    };
    const observer = new ResizeObserver(schedule);
    observer.observe(bar);
    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [usageFullText]);

  useEffect(() => {
    const el = usageScrollRef.current;
    if (!usageHovered || !usageCompact || !el) return;
    if (el.scrollWidth <= el.clientWidth) return;
    let frame = 0;
    let last = performance.now();
    let direction = 1;
    const tick = (now: number) => {
      const delta = Math.min(now - last, 50);
      last = now;
      if (!usagePausedRef.current) {
        const max = el.scrollWidth - el.clientWidth;
        let next =
          el.scrollLeft + direction * USAGE_SCROLL_SPEED_PX_PER_MS * delta;
        if (next >= max) {
          next = max;
          direction = -1;
        } else if (next <= 0) {
          next = 0;
          direction = 1;
        }
        el.scrollLeft = next;
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [usageHovered, usageCompact, usageFullText]);

  const handleUsageEnter = () => {
    usagePausedRef.current = false;
    setUsageHovered(true);
    if (usageScrollRef.current) usageScrollRef.current.scrollLeft = 0;
  };
  const handleUsageLeave = () => {
    usagePausedRef.current = false;
    setUsageHovered(false);
    if (usageScrollRef.current) usageScrollRef.current.scrollLeft = 0;
  };

  // 单一事实来源：澄清卡只渲染 timeline；messages 不再参与运行态锁定，
  // 避免 messages 里残留的 pending 澄清把 Footer 永久锁在「执行中/停止」。
  const hasPendingClarifications = timeline.some(
    (r: any) => r.kind === 'clarification' && r.state === 'PENDING',
  );

  // 实例未开放主站会话时禁用输入（服务端同样会拦，这里只为体验一致）
  const consoleBlocked =
    instances.find((i: any) => i.id === currentSession?.agentInstanceId)
      ?.allowConsoleSession === false;

  const footerProps = {
    inputValue,
    onInputValueChange,
    sending,
    hasPendingClarifications,
    onSendMessage,
    onCancel: onCancelSend,
    onExpandOpen,
    sessionKey,
    sessionId: currentSession?.id,
    consoleBlocked,
  };

  return (
    <>
      <Header
        currentSession={currentSession}
        instances={instances}
        selectedModelRouteId={selectedModelRouteId}
        onModelRouteChange={onModelRouteChange}
        modelRoutes={modelRoutes}
        sseConnected={sseConnected}
        sessionPanelOpen={sessionPanelOpen}
        onTogglePanel={onTogglePanel}
      />
      {hasMessages ? (
        <div ref={messagesRef} className={styles.messages}>
          <div
            ref={timelineWrapRef}
            style={{ width: '100%', minWidth: 0, flexShrink: 0 }}
          >
            <TimelineList
              rows={timeline}
              hasMore={timelineHasMore}
              onLoadOlder={onLoadOlderTimeline}
              onRespondClarify={onRespondClarify}
              onCancelClarification={(row: any) =>
                onCancelClarification?.({
                  ...row,
                  sessionId: currentSession?.id,
                })
              }
              loadSubAgentSteps={loadSubAgentSteps}
              subAgentLive={subAgentLive}
            />
          </div>
          {sessionUsage && Number(sessionUsage.totalTokens) > 0 && (
            <div
              ref={usageBarRef}
              onMouseEnter={handleUsageEnter}
              onMouseLeave={handleUsageLeave}
              style={{
                position: 'sticky',
                bottom: 8,
                marginTop: 8,
                alignSelf: 'center',
                maxWidth: 'min(720px, 100%)',
                width: usageCompact ? 'min(720px, 100%)' : 'fit-content',
                padding: '6px 16px',
                borderRadius: 999,
                background: 'rgba(255, 255, 255, 0.72)',
                WebkitBackdropFilter: 'blur(8px)',
                backdropFilter: 'blur(8px)',
                boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                border: '1px solid rgba(0,0,0,0.06)',
                fontSize: 12,
                color: 'rgba(0,0,0,0.55)',
                zIndex: 10,
                pointerEvents: 'auto',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                boxSizing: 'border-box',
              }}
            >
              <div
                ref={usageScrollRef}
                className={styles.usageBarScroll}
                onPointerDown={() => {
                  usagePausedRef.current = true;
                }}
                style={{
                  display: 'block',
                  maxWidth: '100%',
                  overflowX: usageHovered ? 'auto' : 'hidden',
                  overflowY: 'hidden',
                  whiteSpace: 'nowrap',
                  textOverflow: usageHovered ? 'clip' : 'ellipsis',
                  textAlign: usageHovered ? 'left' : 'center',
                  cursor: usageHovered ? 'text' : 'default',
                }}
              >
                {usageBaseText}
                {showPromptCompletion && usagePromptPart}
                {showPromptCompletion && usageCompletionPart}
                {sessionCacheRate != null && (
                  <span
                    title={`${intl.formatMessage(
                      {
                        id: 'pages.chat.usage.cacheDetail',
                        defaultMessage: 'Hit {hit} / miss {miss}',
                      },
                      {
                        hit: formatTokens(sessionUsage.cacheHitTokens),
                        miss: formatTokens(sessionUsage.cacheMissTokens),
                      },
                    )} · ${intl.formatMessage({
                      id: 'pages.chat.sessionUsage.scope',
                      defaultMessage:
                        'Session total (incl. compaction/title/sub-agents)',
                    })}`}
                  >
                    {usageCachePart}
                  </span>
                )}
                {contextPercent != null && (
                  <span
                    title={`${formatTokens(sessionUsage.latestPromptTokens)} / ${formatTokens(currentRoute?.maxInputTokens)}`}
                  >
                    {usageContextPart}
                  </span>
                )}
              </div>
              <span
                ref={usageFullMeasureRef}
                aria-hidden="true"
                style={{
                  position: 'absolute',
                  left: 0,
                  top: 0,
                  visibility: 'hidden',
                  whiteSpace: 'nowrap',
                  pointerEvents: 'none',
                  fontSize: 12,
                  lineHeight: '18px',
                }}
              >
                {usageFullText}
              </span>
            </div>
          )}
        </div>
      ) : (
        <div className={styles.footerCenter}>
          <Footer {...footerProps} />
        </div>
      )}
      {hasMessages && (
        <div className={styles.footer}>
          <Footer {...footerProps} />
        </div>
      )}
    </>
  );
}

import { useEffect, useRef } from 'react';
import { useStyles } from '../../style';
import { formatTokens } from '../Usage';
import Footer from './Footer';
import Header from './Header';
import type { SubAgentLiveMap, SubAgentTimelineItem } from './subAgentTypes';
import TimelineList from './TimelineList';

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
  const { styles } = useStyles();
  const messagesRef = useRef<HTMLDivElement>(null);
  const timelineWrapRef = useRef<HTMLDivElement>(null);

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

  const hasPendingClarifications =
    messages.some((m: any) =>
      m.clarifications?.some((c: any) => c.status === 'pending'),
    ) ||
    timeline.some(
      (r: any) => r.kind === 'clarification' && r.state === 'PENDING',
    );

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
              style={{
                position: 'sticky',
                bottom: 8,
                marginTop: 8,
                alignSelf: 'center',
                maxWidth: 640,
                width: 'auto',
                padding: '4px 14px',
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
              }}
            >
              ♨ 会话用量 · {formatTokens(sessionUsage.totalTokens)} tokens
              {formatTokens(sessionUsage.promptTokens) !== '0' &&
                ` · prompt ${formatTokens(sessionUsage.promptTokens)}`}
              {formatTokens(sessionUsage.completionTokens) !== '0' &&
                ` · completion ${formatTokens(sessionUsage.completionTokens)}`}
              {sessionUsage.cacheHitRate != null &&
                ` · cache ${sessionUsage.cacheHitRate}%`}
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

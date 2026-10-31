import { useIntl } from '@umijs/max';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useStyles } from '../../style';
import ClarificationDrawer, {
  type ClarificationBatch,
} from '../ClarificationDrawer';
import ToolApprovalDrawer, {
  type ToolApprovalBatch,
} from '../ToolApprovalDrawer';
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
  /** 工具参数仍在生成时的即时提示。 */
  preparingTool?: { publishId?: string; name?: string } | null;
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
  runMode?: 'WRITE' | 'READ_ONLY';
  onRunModeChange?: (mode: 'WRITE' | 'READ_ONLY') => void;
  toolApprovalBatch?: ToolApprovalBatch | null;
  toolApprovalSubmitting?: boolean;
  onResolveToolApproval?: (
    decisions: Array<{ callId: string; decision: string }>,
  ) => void;
  clarificationBatch?: ClarificationBatch | null;
  clarificationSubmitting?: boolean;
  onResolveClarifications?: (
    answers: Array<{
      clarificationId: string;
      selected?: string[];
      text?: string;
    }>,
  ) => void;
  onCancelClarifications?: () => void;
  queuedMessages?: any[];
  onWithdrawQueued?: (queueId: string) => void;
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
  preparingTool,
  inputValue,
  onInputValueChange,
  sending,
  onSendMessage,
  onCancelSend,
  onExpandOpen,
  sessionPanelOpen,
  onTogglePanel,
  runMode = 'WRITE',
  onRunModeChange,
  toolApprovalBatch,
  toolApprovalSubmitting,
  onResolveToolApproval,
  clarificationBatch,
  clarificationSubmitting,
  onResolveClarifications,
  onCancelClarifications,
  queuedMessages = [],
  onWithdrawQueued,
}: ChatMainProps) {
  const sessionKey = currentSession?.id || '';
  const intl = useIntl();
  const { styles } = useStyles();
  const messagesRef = useRef<HTMLDivElement>(null);
  const footerRef = useRef<HTMLDivElement>(null);
  const [footerHeight, setFooterHeight] = useState(0);
  const timelineWrapRef = useRef<HTMLDivElement>(null);

  const hasMessages =
    messages.some((m: any) => m.content && m.content !== '{}') ||
    timeline.length > 0;

  // 单一滚动容器（.messages）接手消息滚动：
  // - stickRef 在 scroll 事件持续记录「用户是否在底部附近」（增长前的位置，解决大块增长 >160px 不自滚）；
  // - ResizeObserver 观察内容包裹层，任何内容高度变化（子 Agent 展开/异步步骤/SSE 流式/工具块展开/图片加载/大块合并）
  //   都在「用户原本在底部」时滚到底，不打断向上回看 / 加载更早。
  const stickRef = useRef(true);
  const programmaticScrollRef = useRef(false);
  const lastScrollHeightRef = useRef(0);
  const lastScrollTopRef = useRef(0);
  useEffect(() => {
    const el = messagesRef.current;
    const inner = timelineWrapRef.current;
    if (!el || !inner) return;
    lastScrollHeightRef.current = el.scrollHeight;
    lastScrollTopRef.current = el.scrollTop;

    const scrollToBottom = () => {
      if (!stickRef.current) return;
      programmaticScrollRef.current = true;
      el.scrollTop = el.scrollHeight;
      lastScrollHeightRef.current = el.scrollHeight;
      lastScrollTopRef.current = el.scrollTop;
      requestAnimationFrame(() => {
        programmaticScrollRef.current = false;
      });
    };
    const onScroll = () => {
      if (programmaticScrollRef.current) return;
      const height = el.scrollHeight;
      const scrollTop = el.scrollTop;
      const grew = height > lastScrollHeightRef.current + 1;
      const userScrolledUp = scrollTop < lastScrollTopRef.current - 1;
      lastScrollHeightRef.current = height;
      lastScrollTopRef.current = scrollTop;
      const distance = height - scrollTop - el.clientHeight;
      // 内容增长本身也会触发 scroll（滚动锚定/浏览器夹紧），不能据此判定用户离开底部
      if (grew && stickRef.current && !userScrolledUp) return;
      stickRef.current = distance < 160;
    };
    onScroll();
    el.addEventListener('scroll', onScroll, { passive: true });
    const ro = new ResizeObserver(() => {
      lastScrollHeightRef.current = el.scrollHeight;
      scrollToBottom();
    });
    ro.observe(inner);
    scrollToBottom();
    return () => {
      el.removeEventListener('scroll', onScroll);
      ro.disconnect();
    };
  }, [hasMessages]);

  // 长消息 / 待办 / 截图等新行到达时，ResizeObserver 之外再补一次钉底。
  const lastTimelineKey = (() => {
    const last = timeline[timeline.length - 1];
    return `${timeline.length}:${last?.seq ?? ''}:${last?.state ?? ''}`;
  })();
  useEffect(() => {
    if (!stickRef.current) return;
    const el = messagesRef.current;
    if (!el) return;
    const raf = requestAnimationFrame(() => {
      if (!stickRef.current) return;
      programmaticScrollRef.current = true;
      el.scrollTop = el.scrollHeight;
      lastScrollHeightRef.current = el.scrollHeight;
      lastScrollTopRef.current = el.scrollTop;
      requestAnimationFrame(() => {
        programmaticScrollRef.current = false;
      });
    });
    return () => cancelAnimationFrame(raf);
  }, [lastTimelineKey]);

  // 单一事实来源：澄清卡只渲染 timeline；messages 不再参与运行态锁定，
  // 避免 messages 里残留的 pending 澄清把 Footer 永久锁在「执行中/停止」。
  const hasPendingClarifications =
    Boolean(clarificationBatch?.items?.length) ||
    timeline.some(
      (r: any) => r.kind === 'clarification' && r.state === 'PENDING',
    );

  // 实例未开放主站会话时禁用输入（服务端同样会拦，这里只为体验一致）
  const consoleBlocked =
    instances.find((i: any) => i.id === currentSession?.agentInstanceId)
      ?.allowConsoleSession === false;

  useEffect(() => {
    const el = footerRef.current;
    if (!el) {
      setFooterHeight(0);
      return;
    }
    const update = () => setFooterHeight(el.offsetHeight);
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [hasMessages, consoleBlocked, inputValue, sending]);

  const footerProps = {
    inputValue,
    onInputValueChange,
    sending,
    hasPendingClarifications,
    hasPendingToolApproval: Boolean(toolApprovalBatch),
    onSendMessage,
    onCancel: onCancelSend,
    onExpandOpen,
    sessionKey,
    sessionId: currentSession?.id,
    consoleBlocked,
    runMode,
    onRunModeChange,
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
      <div className={styles.chatStage}>
        {hasMessages ? (
          <div ref={messagesRef} className={styles.messages}>
            <div
              ref={timelineWrapRef}
              style={{ width: '100%', minWidth: 0, flexShrink: 0 }}
            >
              <TimelineList
                rows={timeline}
                hasMore={timelineHasMore}
                sessionId={currentSession?.id}
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
                preparingTool={preparingTool}
              />
            </div>
          </div>
        ) : (
          <div className={styles.footerCenter}>
            <Footer {...footerProps} />
          </div>
        )}
        {queuedMessages.length > 0 && (
          <div
            style={{
              padding: '4px 16px 0',
              display: 'flex',
              flexDirection: 'column',
              gap: 4,
            }}
          >
            {queuedMessages.map((item: any) => (
              <div
                key={item.queueId}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  fontSize: 12,
                  color: '#8c8c8c',
                  background: '#fffbe6',
                  border: '1px solid #ffe58f',
                  borderRadius: 8,
                  padding: '4px 8px',
                }}
              >
                <span
                  style={{
                    flex: 1,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  排队中：{item.text}
                </span>
                <button
                  type="button"
                  onClick={() => onWithdrawQueued?.(item.queueId)}
                  style={{
                    border: 'none',
                    background: 'transparent',
                    color: '#1677ff',
                    cursor: 'pointer',
                  }}
                >
                  撤回
                </button>
              </div>
            ))}
          </div>
        )}
        {hasMessages && (
          <div ref={footerRef} className={styles.footer}>
            <Footer {...footerProps} />
          </div>
        )}
        <ToolApprovalDrawer
          open={!!toolApprovalBatch}
          batch={toolApprovalBatch || null}
          submitting={toolApprovalSubmitting}
          bottomOffset={footerHeight}
          onResolve={(decisions) => onResolveToolApproval?.(decisions)}
        />
        <ClarificationDrawer
          open={!!clarificationBatch?.items?.length}
          batch={clarificationBatch || null}
          submitting={clarificationSubmitting}
          bottomOffset={footerHeight}
          onResolve={(answers) => onResolveClarifications?.(answers)}
          onCancel={onCancelClarifications}
        />
      </div>
    </>
  );
}

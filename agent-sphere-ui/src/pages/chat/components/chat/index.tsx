import { useIntl } from '@umijs/max';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { STATUS } from '@/constants/status';
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
  reasoningCapability?: any;
  reasoningEffort?: string;
  onReasoningChange: (level: string) => void;
  sseConnected: boolean;
  messages: any[];
  /** Flattened unified timeline rows (replaces the old multi-source assembly rendering). */
  timeline: any[];
  timelineHasMore: boolean;
  onLoadOlderTimeline: () => void;
  onRespondClarify: (row: any, response: string) => void;
  loadSubAgentSteps: (id: number) => Promise<SubAgentTimelineItem[]>;
  /** Sub-agent live steps (purely SSE-driven, aggregated by subAgentRunId). */
  subAgentLive?: SubAgentLiveMap;
  /** Immediate hint while tool arguments are still being generated. */
  preparingTool?: { publishId?: string; name?: string } | null;
  hasMoreHistory: boolean;
  onLoadMoreHistory: () => void;
  inputValue: string;
  onInputValueChange: (v: string) => void;
  sending: boolean;
  /** Authoritative runtime values (aggregated from source tables): the single source for the send lock / pending judgment. */
  runtimeState?: any;
  onSendMessage: () => void;
  onCancelSend: () => void;
  onCancelClarification?: (clarification: any) => void;
  onExpandOpen: () => void;
  sessionPanelOpen: boolean;
  onTogglePanel: () => void;
  /** Session-level usage aggregation (shown as a floating bar at the bottom of the chat area). */
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
  /**
   * Training surface drives a Worker instance whose console switch is intentionally off; the server
   * allows the owner through, so the UX lock must be lifted here as well.
   */
  trainingMode?: boolean;
  /** Training hides the console session panel (its Worker rail already shows the live state). */
  hideSessionPanel?: boolean;
  /** Internal sessions (router/worker/group router) are view-only. */
  readOnly?: boolean;
}

export default function ChatMain({
  currentSession,
  instances,
  selectedModelRouteId,
  onModelRouteChange,
  modelRoutes,
  reasoningCapability,
  reasoningEffort,
  onReasoningChange,
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
  runtimeState,
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
  trainingMode = false,
  hideSessionPanel = false,
  readOnly = false,
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

  // A single scroll container (.messages) owns message scrolling:
  // - stickRef continuously records "is the user near the bottom" on scroll (position before
  //   growth, fixing large >160px growth that would not auto-scroll);
  // - ResizeObserver watches the content wrapper: any content height change (sub-agent expand /
  //   async steps / SSE streaming / tool block expand / image load / large merge) scrolls to the
  //   bottom while "the user was already at the bottom", without interrupting scroll-up / older loads.
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
      // Content growth itself also fires scroll (scroll anchoring / browser clamping), so it
      // cannot be used to decide the user left the bottom
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

  // When new rows arrive (long messages / todos / screenshots), pin to the bottom once more
  // in addition to the ResizeObserver.
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

  // The input lock trusts only the runtime-state API (aggregated from source tables):
  // the timeline is only responsible for rendering and does not take part in decisions
  const hasPendingClarifications =
    Number(runtimeState?.pendingClarifications || 0) > 0;

  // Disable input when the instance has console sessions disabled (the server enforces this
  // too; here it is only for consistent UX). Training sessions are the exception: they are created
  // on a Worker instance with the console switch off, and the server lets the owner drive them.
  const consoleBlocked =
    !trainingMode &&
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
    hasPendingToolApproval: Number(runtimeState?.pendingToolApprovals || 0) > 0,
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
        reasoningCapability={reasoningCapability}
        reasoningEffort={reasoningEffort}
        onReasoningChange={onReasoningChange}
        sseConnected={sseConnected}
        sessionPanelOpen={sessionPanelOpen}
        onTogglePanel={onTogglePanel}
        hideSessionPanel={hideSessionPanel}
      />
      {readOnly && (
        <div
          style={{
            padding: '6px 16px',
            fontSize: 12,
            color: '#8c8c8c',
            background: '#fafafa',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          {intl.formatMessage({
            id: 'pages.chat.readOnlyHint',
            defaultMessage:
              'Read-only conversation: other users or an internal session',
          })}
        </div>
      )}
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
                retryState={
                  runtimeState?.runStatus === STATUS.RUN.WAITING &&
                  runtimeState?.waitKind === 'LLM_RETRY' &&
                  runtimeState?.runId != null
                    ? {
                        runId: Number(runtimeState.runId),
                        attempt: runtimeState.retryAttempt,
                        max: runtimeState.retryMax,
                        remainingMs: Number(runtimeState.retryRemainingMs || 0),
                      }
                    : null
                }
              />
            </div>
          </div>
        ) : (
          <div className={styles.footerCenter}>
            {!readOnly && <Footer {...footerProps} />}
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
                  {intl.formatMessage(
                    {
                      id: 'pages.chat.queued',
                      defaultMessage: 'Queued: {text}',
                    },
                    { text: item.text },
                  )}
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
                  {intl.formatMessage({
                    id: 'pages.chat.withdraw',
                    defaultMessage: 'Withdraw',
                  })}
                </button>
              </div>
            ))}
          </div>
        )}
        {hasMessages && !readOnly && (
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

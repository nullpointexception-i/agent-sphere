import { useIntl } from '@umijs/max';
import { Button } from 'antd';
import { useEffect, useRef } from 'react';
import { useStyles } from '../../style';
import Footer from './Footer';
import Header from './Header';
import MessageList from './MessageList';
import SubAgentDock from './SubAgentDock';
import type { SubAgentTimelineItem } from './subAgentTypes';

interface ChatMainProps {
  currentSession: any;
  instances: any[];
  selectedModelRouteId?: number;
  onModelRouteChange: (id: number | undefined) => void;
  modelRoutes: any[];
  sseConnected: boolean;
  messages: any[];
  collapsedKeys: Set<string>;
  onCollapsedKeysChange: (keys: Set<string>) => void;
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
  subAgentLive: any[];
  subAgentHistorical: any[];
  onLoadSubAgentTimeline: (id: number) => Promise<SubAgentTimelineItem[]>;
  mainTimeline: any[];
  historyTimeline: any[];
}

export default function ChatMain({
  currentSession,
  instances,
  selectedModelRouteId,
  onModelRouteChange,
  modelRoutes,
  sseConnected,
  messages,
  collapsedKeys,
  onCollapsedKeysChange,
  hasMoreHistory,
  onLoadMoreHistory,
  inputValue,
  onInputValueChange,
  sending,
  onSendMessage,
  onCancelSend,
  onCancelClarification,
  onExpandOpen,
  sessionPanelOpen,
  onTogglePanel,
  subAgentLive,
  subAgentHistorical,
  onLoadSubAgentTimeline,
  mainTimeline,
  historyTimeline,
}: ChatMainProps) {
  const sessionKey = currentSession?.id || '';
  const intl = useIntl();
  const { styles } = useStyles();
  const loadMoreRef = useRef<HTMLDivElement>(null);
  const messagesRef = useRef<HTMLDivElement>(null);

  // 单一滚动容器（.messages）接手消息滚动：仅在用户已接近底部时滚到底（含子 Agent chip 栏），
  // 不打断向上回看历史 / 加载更多。
  useEffect(() => {
    const el = messagesRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 160;
    if (nearBottom) el.scrollTop = el.scrollHeight;
  }, [
    messages,
    mainTimeline,
    historyTimeline,
    subAgentLive,
    subAgentHistorical,
  ]);

  const hasMessages = messages.some(
    (m: any) => m.content && m.content !== '{}',
  );

  const hasPendingClarifications = messages.some((m: any) =>
    m.clarifications?.some((c: any) => c.status === 'pending'),
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
          <div ref={loadMoreRef} className={styles.loadMore}>
            {hasMoreHistory && (
              <Button type="link" size="small" onClick={onLoadMoreHistory}>
                {intl.formatMessage({
                  id: 'chat.loadMoreMessages',
                  defaultMessage: 'Load more messages',
                })}
              </Button>
            )}
          </div>
          <MessageList
            messages={messages}
            collapsedKeys={collapsedKeys}
            onCollapsedKeysChange={onCollapsedKeysChange}
            onCancelClarification={onCancelClarification}
            mainTimeline={mainTimeline}
            historyTimeline={historyTimeline}
          />
          <SubAgentDock
            live={subAgentLive}
            historical={subAgentHistorical}
            loadTimeline={onLoadSubAgentTimeline}
          />
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

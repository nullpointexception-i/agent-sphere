import { useIntl } from '@umijs/max';
import { Button } from 'antd';
import { useRef } from 'react';
import { useStyles } from '../../style';
import Footer from './Footer';
import Header from './Header';
import MessageList from './MessageList';

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
  onExpandOpen: () => void;
  sessionPanelOpen: boolean;
  onTogglePanel: () => void;
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
  onExpandOpen,
  sessionPanelOpen,
  onTogglePanel,
}: ChatMainProps) {
  const sessionKey = currentSession?.id || '';
  const intl = useIntl();
  const { styles } = useStyles();
  const loadMoreRef = useRef<HTMLDivElement>(null);

  const hasMessages = messages.some(
    (m: any) => m.content && m.content !== '{}',
  );

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
        <div className={styles.messages}>
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
          />
        </div>
      ) : (
        <div className={styles.footerCenter}>
          <Footer
            inputValue={inputValue}
            onInputValueChange={onInputValueChange}
            sending={sending}
            onSendMessage={onSendMessage}
            onCancel={onCancelSend}
            onExpandOpen={onExpandOpen}
            sessionKey={sessionKey}
            sessionId={currentSession?.id}
          />
        </div>
      )}
      {hasMessages && (
        <div className={styles.footer}>
          <Footer
            inputValue={inputValue}
            onInputValueChange={onInputValueChange}
            sending={sending}
            onSendMessage={onSendMessage}
            onCancel={onCancelSend}
            onExpandOpen={onExpandOpen}
            sessionKey={sessionKey}
            sessionId={currentSession?.id}
          />
        </div>
      )}
    </>
  );
}

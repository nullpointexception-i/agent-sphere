import { Avatar, Dropdown, Empty, Spin } from 'antd';
import type { RefObject } from 'react';
import { STATUS } from '@/constants/status';
import type {
  ImConversation,
  ImMessage,
  SessionInputDecision,
} from '@/services/agentSphere/im';
import { userDisplayLabel } from '@/utils/userLabel';
import { messageText } from '../../im/components/messageUtils';
import {
  type AgentClarificationAnswer,
  AgentInputCard,
  ROUTER_APPROVAL,
  ROUTER_CLARIFICATION,
} from '../AgentInputCard';
import { AgentStreamBubble } from '../AgentStreamBubble';
import { AGENT_STREAM_PHASE } from '../agentStreamStore';
import { CollapsibleMarkdown } from '../CollapsibleMarkdown';
import {
  isSameUser,
  messageTime,
  type PendingMessage,
  taskCardOf,
  taskStatusLabel,
} from '../conversationModel';
import { tw } from '../i18n';
import { MessageMarkdown } from '../MessageMarkdown';
import { avatarStyle, displayName, initials } from '../shared';

export interface MessageListProps {
  messages: ImMessage[];
  participants: any[];
  conversation: ImConversation | null;
  me: any;
  styles: Record<string, string>;
  conversationId: number;
  loading: boolean;
  hasOlder: boolean;
  olderCursor?: number;
  olderLoading: boolean;
  listRef: RefObject<HTMLDivElement | null>;
  agentStream: any;
  agentParticipant: any;
  onBottomChange: (atBottom: boolean) => void;
  onLoadOlder: () => void;
  onMessageAction: (item: ImMessage, key: string) => void;
  onRetryPending: (item: PendingMessage) => void;
  onAnswerClarification: (
    inputId: string,
    payload: AgentClarificationAnswer,
  ) => Promise<boolean>;
  onApproveSessionInput: (
    conversationId: number,
    inputId: string,
    decisions: SessionInputDecision[],
  ) => Promise<boolean>;
}

/** Message wall: history pagination, bubbles, task cards and stream placeholder. */
export function MessageList({
  messages,
  participants,
  conversation,
  me,
  styles,
  conversationId,
  loading,
  hasOlder,
  olderCursor,
  olderLoading,
  listRef,
  agentStream,
  agentParticipant,
  onBottomChange,
  onLoadOlder,
  onMessageAction,
  onRetryPending,
  onAnswerClarification,
  onApproveSessionInput,
}: MessageListProps) {
  return (
    <div
      className={styles.messages}
      ref={listRef}
      onScroll={(event) => {
        const element = event.currentTarget;
        const distanceToBottom =
          element.scrollHeight - element.scrollTop - element.clientHeight;
        onBottomChange(distanceToBottom < 48);
        if (
          hasOlder &&
          olderCursor &&
          !olderLoading &&
          element.scrollTop < 48
        ) {
          onLoadOlder();
        }
      }}
    >
      <Spin spinning={loading}>
        {messages.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={tw('conversation.noMessages', 'No messages')}
          />
        ) : (
          messages.map((item) => {
            const pending = item.id < 0;
            const sender = participants.find(
              (member) =>
                member.participantType === item.senderType &&
                Number(member.participantId) === Number(item.senderId),
            );
            const self =
              item.senderType === 'USER' &&
              (sender
                ? isSameUser(sender, me)
                : Number(item.senderId) === Number(me?.id));
            const ownAgent =
              item.senderType === 'AGENT' &&
              item.ownAgent === true &&
              conversation?.type === 'GROUP';
            const alignRight = self || ownAgent;
            const serverLabel = item.senderName
              ? `${item.senderName}${
                  item.senderOwnerName
                    ? tw('chat.ownerSuffix', ' ({owner})', {
                        owner: item.senderOwnerName,
                      })
                    : ''
                }`
              : null;
            const senderName =
              serverLabel ||
              (sender
                ? userDisplayLabel(
                    sender,
                    item.senderType === 'AGENT'
                      ? tw('chat.agent', 'Digital avatar')
                      : tw('chat.unknownUser', 'Unknown user'),
                  )
                : item.senderType === 'AGENT'
                  ? tw('chat.agent', 'Digital avatar')
                  : item.senderType === 'SYSTEM'
                    ? tw('chat.system', 'System')
                    : tw('chat.unknownUser', 'Unknown user'));
            const avatarName = self
              ? displayName(me)
              : item.senderName || senderName;
            const card = taskCardOf(item);
            const pinned = conversation?.pinnedMessage?.id === item.id;
            const menuItems: any[] = [
              pinned
                ? { key: 'unpin', label: tw('conversation.unpin', 'Unpin') }
                : { key: 'pin', label: tw('conversation.pin', 'Pin') },
            ];
            if (self) {
              menuItems.push({ type: 'divider' });
              menuItems.push({
                key: 'delete',
                label: tw('common.delete', 'Delete'),
                danger: true,
              });
            }
            return (
              <Dropdown
                key={item.id}
                disabled={pending}
                trigger={['contextMenu']}
                menu={{
                  items: menuItems,
                  onClick: ({ key }) => onMessageAction(item, key),
                }}
              >
                <div
                  className={`${styles.messageRow} ${alignRight ? styles.messageRowSelf : ''}`}
                >
                  <Avatar
                    size={32}
                    src={self ? me?.avatar : sender?.avatar}
                    style={avatarStyle(avatarName)}
                  >
                    {initials(avatarName)}
                  </Avatar>
                  <div
                    className={`${styles.bubbleWrap} ${alignRight ? styles.bubbleWrapSelf : ''}`}
                  >
                    <div
                      className={`${styles.messageMeta} ${alignRight ? styles.messageMetaSelf : ''}`}
                    >
                      {self ? tw('chat.me', 'Me') : senderName}
                      {' · '}
                      {messageTime(item.createdAt)}
                    </div>
                    {card ? (
                      card.cardType === ROUTER_CLARIFICATION ||
                      card.cardType === ROUTER_APPROVAL ? (
                        <AgentInputCard
                          card={card}
                          conversationId={conversationId}
                          canView={item.canView !== false}
                          canAnswer={item.canAnswer !== false}
                          deniedReason={item.deniedReason}
                          onAnswer={onAnswerClarification}
                          onApprove={onApproveSessionInput}
                        />
                      ) : (
                        <div className={styles.taskCard}>
                          <div className={styles.taskCardTop}>
                            <span className={styles.taskCardTitle}>
                              {card.title || tw('chat.taskFallback', 'Task')}
                            </span>
                            <span
                              className={`${styles.taskCardStatus} ${
                                card.status === STATUS.WORK_ITEM.COMPLETED ||
                                card.status === STATUS.WORK_ITEM.DONE
                                  ? styles.taskCardStatusDone
                                  : card.status === STATUS.WORK_ITEM.FAILED ||
                                      card.status === STATUS.WORK_ITEM.CANCELLED
                                    ? styles.taskCardStatusFailed
                                    : styles.taskCardStatusRunning
                              }`}
                            >
                              {taskStatusLabel(card.status)}
                            </span>
                          </div>
                          {card.summary ? (
                            <div className={styles.taskCardSummary}>
                              <CollapsibleMarkdown text={card.summary} />
                            </div>
                          ) : (
                            <div className={styles.taskCardSummary}>
                              {tw(
                                'chat.taskCreated',
                                'Task created and in progress',
                              )}
                            </div>
                          )}
                        </div>
                      )
                    ) : item.type === 'TEXT' &&
                      item.senderType === 'AGENT' &&
                      messageText(item) ? (
                      <div className={styles.bubble}>
                        <MessageMarkdown text={messageText(item) || ''} />
                      </div>
                    ) : (
                      <div
                        className={`${styles.bubble} ${self ? styles.bubbleSelf : ''}`}
                      >
                        {messageText(item) ||
                          tw('chat.messageFallback', '[Message]')}
                      </div>
                    )}
                    {pending ? (
                      item.status === 'FAILED' ? (
                        <button
                          type="button"
                          className={styles.messageRetry}
                          title={(item as PendingMessage).error}
                          onClick={() => onRetryPending(item as PendingMessage)}
                        >
                          {tw('chat.sendFailedRetry', 'Failed to send · Retry')}
                        </button>
                      ) : (
                        <span className={styles.messageSending}>
                          {tw('chat.sending', 'Sending...')}
                        </span>
                      )
                    ) : null}
                  </div>
                </div>
              </Dropdown>
            );
          })
        )}
      </Spin>
      {agentStream && agentParticipant ? (
        <AgentStreamBubble
          state={agentStream}
          agent={agentParticipant}
          hidden={
            agentStream.phase === AGENT_STREAM_PHASE.WAITING &&
            messages[messages.length - 1]?.senderType !== 'USER'
          }
        />
      ) : null}
    </div>
  );
}

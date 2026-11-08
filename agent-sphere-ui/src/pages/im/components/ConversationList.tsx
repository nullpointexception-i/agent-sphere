import { PlusOutlined, TeamOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  Avatar,
  Badge,
  Button,
  Empty,
  Listy,
  Spin,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { Can } from '@/components/Can';
import { PERM } from '@/constants/permissions';
import type { ImConversation } from '@/services/agentSphere/im';
import { messageText } from './messageUtils';

interface Props {
  conversations: ImConversation[];
  activeId?: number;
  loading: boolean;
  hasMore?: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
  onSelect: (id: number) => void;
  onCreate: () => void;
  labels: {
    conversations: string;
    newConversation: string;
    noConversation: string;
  };
}

function conversationLabel(
  conversation: ImConversation,
  intl: ReturnType<typeof useIntl>,
) {
  if (conversation.type === 'GROUP') {
    return (
      conversation.remark ||
      conversation.displayName ||
      conversation.title ||
      intl.formatMessage({ id: 'pages.im.group', defaultMessage: 'Group' })
    );
  }
  return (
    conversation.displayName ||
    conversation.title ||
    intl.formatMessage({ id: 'pages.im.dm', defaultMessage: 'Direct chat' })
  );
}

function conversationPreview(conversation: ImConversation) {
  if (conversation.lastMessage) {
    const text = messageText(conversation.lastMessage).trim();
    if (text) return text;
  }
  return conversation.purpose || '';
}

function conversationTime(value?: string) {
  if (!value) return '';
  const at = dayjs(value);
  return at.isSame(dayjs(), 'day')
    ? at.format('HH:mm')
    : at.format('MM-DD HH:mm');
}

export function ConversationList({
  conversations,
  activeId,
  loading,
  hasMore,
  loadingMore,
  onLoadMore,
  onSelect,
  onCreate,
  labels,
}: Props) {
  const intl = useIntl();
  return (
    <div
      style={{
        width: 320,
        display: 'flex',
        flexDirection: 'column',
        background: '#fff',
        border: '1px solid #eef0f4',
        borderRadius: 14,
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          padding: '14px 16px',
          borderBottom: '1px solid #f0f0f0',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        <Typography.Text strong>{labels.conversations}</Typography.Text>
        <Can code={PERM.imConversationCreate}>
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            onClick={onCreate}
          >
            {labels.newConversation}
          </Button>
        </Can>
      </div>
      <Spin spinning={loading}>
        <div style={{ flex: 1, overflowY: 'auto', padding: 8 }}>
          {conversations.length === 0 ? (
            <Empty description={labels.noConversation} />
          ) : (
            <>
              <Listy
                items={conversations}
                rowKey="id"
                itemRender={(conversation) => {
                  const active = conversation.id === activeId;
                  const preview = conversationPreview(conversation);
                  return (
                    <div
                      onClick={() => onSelect(conversation.id)}
                      style={{
                        cursor: 'pointer',
                        display: 'flex',
                        gap: 10,
                        padding: '10px 10px',
                        marginBottom: 4,
                        borderRadius: 10,
                        background: active ? '#eef2ff' : undefined,
                        transition: 'background 0.15s ease',
                      }}
                    >
                      <Badge
                        count={conversation.unread ?? 0}
                        size="small"
                        offset={[-2, 2]}
                      >
                        <Avatar
                          shape={
                            conversation.type === 'GROUP' ? 'square' : 'circle'
                          }
                          size={40}
                          src={conversation.avatar}
                          icon={<TeamOutlined />}
                          style={{ background: '#f1efff', color: '#6c5ce7' }}
                        />
                      </Badge>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 6,
                          }}
                        >
                          <Typography.Text
                            strong
                            ellipsis
                            style={{ flex: 1, minWidth: 0 }}
                          >
                            {conversationLabel(conversation, intl)}
                          </Typography.Text>
                          <span
                            style={{
                              color: '#a0a4ad',
                              fontSize: 11,
                              flexShrink: 0,
                            }}
                          >
                            {conversationTime(conversation.lastMessageAt)}
                          </span>
                        </div>
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 6,
                            marginTop: 3,
                          }}
                        >
                          <Tag
                            color={
                              conversation.type === 'GROUP' ? 'blue' : 'default'
                            }
                            style={{ marginInlineEnd: 0 }}
                          >
                            {conversation.type === 'GROUP'
                              ? intl.formatMessage({
                                  id: 'pages.im.group',
                                  defaultMessage: 'Group',
                                })
                              : intl.formatMessage({
                                  id: 'pages.im.dm',
                                  defaultMessage: 'Direct',
                                })}
                          </Tag>
                          <Typography.Text
                            type="secondary"
                            ellipsis
                            style={{ fontSize: 12, flex: 1, minWidth: 0 }}
                          >
                            {preview || '-'}
                          </Typography.Text>
                        </div>
                      </div>
                    </div>
                  );
                }}
              />
              {hasMore ? (
                <Button
                  block
                  type="text"
                  loading={loadingMore}
                  onClick={onLoadMore}
                  style={{ marginTop: 8 }}
                >
                  {intl.formatMessage({
                    id: 'pages.im.loadMore',
                    defaultMessage: 'Load more',
                  })}
                </Button>
              ) : null}
            </>
          )}
        </div>
      </Spin>
    </div>
  );
}

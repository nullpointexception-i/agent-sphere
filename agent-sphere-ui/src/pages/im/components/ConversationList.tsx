import { PlusOutlined, TeamOutlined } from '@ant-design/icons';
import {
  Badge,
  Button,
  Empty,
  Listy,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { Can } from '@/components/Can';
import type { ImConversation } from '@/services/agentSphere/im';

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
  return (
    <div
      style={{
        width: 300,
        borderRight: '1px solid #f0f0f0',
        paddingRight: 12,
      }}
    >
      <Space
        style={{
          width: '100%',
          justifyContent: 'space-between',
          marginBottom: 12,
        }}
      >
        <Typography.Text strong>{labels.conversations}</Typography.Text>
        <Can code="im:conversation:create">
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            onClick={onCreate}
          >
            {labels.newConversation}
          </Button>
        </Can>
      </Space>
      <Spin spinning={loading}>
        {conversations.length === 0 ? (
          <Empty description={labels.noConversation} />
        ) : (
          <>
            <Listy
              items={conversations}
              rowKey="id"
              itemRender={(conversation) => (
                <div
                  onClick={() => onSelect(conversation.id)}
                  style={{
                    cursor: 'pointer',
                    padding: '10px 8px',
                    borderRadius: 8,
                    background:
                      conversation.id === activeId ? '#e6f4ff' : undefined,
                  }}
                >
                  <Space align="start">
                    <TeamOutlined />
                    <div>
                      <Space>
                        <span
                          style={{
                            display: 'inline-block',
                            maxWidth: 170,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            verticalAlign: 'bottom',
                          }}
                        >
                          {conversation.type === 'GROUP'
                            ? conversation.remark ||
                              conversation.title ||
                              `群聊 #${conversation.id}`
                            : conversation.displayName ||
                              conversation.title ||
                              `单聊 #${conversation.id}`}
                        </span>
                        <Tag>{conversation.type}</Tag>
                        <Badge count={conversation.unread ?? 0} size="small" />
                      </Space>
                      <div style={{ color: '#999' }}>
                        {conversation.lastMessageAt || conversation.status}
                      </div>
                    </div>
                  </Space>
                </div>
              )}
            />
            {hasMore ? (
              <Button
                block
                type="text"
                loading={loadingMore}
                onClick={onLoadMore}
                style={{ marginTop: 8 }}
              >
                加载更多
              </Button>
            ) : null}
          </>
        )}
      </Spin>
    </div>
  );
}

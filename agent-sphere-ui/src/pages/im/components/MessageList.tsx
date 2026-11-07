import { PERM } from '@/constants/permissions';
import { useIntl } from '@umijs/max';
import {
  Button,
  Empty,
  Image,
  Listy,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import type { RefObject } from 'react';
import { useEffect, useState } from 'react';
import { Can } from '@/components/Can';
import { type ImMessage, imApi } from '@/services/agentSphere/im';
import { messageText, parsedContent } from './messageUtils';

const TASK_STATUS: Record<string, { color: string; labelKey: string }> = {
  QUEUED: { color: 'blue', labelKey: 'pages.im.status.queued' },
  DISPATCHING: { color: 'processing', labelKey: 'pages.im.status.dispatching' },
  RUNNING: { color: 'processing', labelKey: 'pages.im.status.running' },
  WAITING: { color: 'gold', labelKey: 'pages.im.status.waiting' },
  COMPLETED: { color: 'green', labelKey: 'pages.im.status.completed' },
  DONE: { color: 'green', labelKey: 'pages.im.status.completed' },
  FAILED: { color: 'red', labelKey: 'pages.im.status.failed' },
  CANCELLED: { color: 'default', labelKey: 'pages.im.status.cancelled' },
};

function AttachmentMessage({
  conversationId,
  content,
  type,
}: {
  conversationId: number;
  content: Record<string, any>;
  type: string;
}) {
  const [url, setUrl] = useState<string>();
  const fileKey = content.fileKey;

  useEffect(() => {
    if (!fileKey) return;
    let alive = true;
    imApi
      .downloadAttachment(conversationId, fileKey)
      .then((blob) => {
        if (alive) setUrl(URL.createObjectURL(blob));
      })
      .catch(() => {
        // Fall through to a plain link when preview loading fails.
      });
    return () => {
      alive = false;
    };
  }, [conversationId, fileKey]);

  useEffect(
    () => () => {
      if (url) URL.revokeObjectURL(url);
    },
    [url],
  );

  if (!fileKey) return <Typography.Text>{content.text ?? '-'}</Typography.Text>;
  if (type === 'IMAGE') {
    return url ? (
      <Image
        src={url}
        alt={content.fileName}
        style={{ maxWidth: 320, maxHeight: 320, borderRadius: 8 }}
      />
    ) : (
      <Typography.Text type="secondary">{content.fileName}</Typography.Text>
    );
  }
  return url ? (
    <Button
      type="link"
      href={url}
      download={content.fileName}
      style={{ padding: 0 }}
    >
      {content.fileName}
    </Button>
  ) : (
    <Typography.Text>{content.fileName}</Typography.Text>
  );
}

function TaskCard({
  content,
  onRetry,
  onCancel,
  onTimeline,
}: {
  content: Record<string, any>;
  onRetry?: () => void;
  onCancel?: () => void;
  onTimeline?: () => void;
}) {
  const status = String(content.status ?? '');
  const intl = useIntl();
  const cfg = TASK_STATUS[status];
  const canRetry = status === 'FAILED' || status === 'CANCELLED';
  const canCancel = ['QUEUED', 'DISPATCHING', 'RUNNING', 'WAITING'].includes(
    status,
  );
  return (
    <div
      style={{
        marginTop: 6,
        padding: 12,
        border: '1px solid #d9d9d9',
        borderRadius: 8,
        background: '#fafafa',
        maxWidth: 520,
      }}
    >
      <Space style={{ marginBottom: 6 }}>
        <Tag color="blue">
          {intl.formatMessage({
            id: 'pages.im.task',
            defaultMessage: 'Task',
          })}
        </Tag>
        {cfg ? (
          <Tag color={cfg.color}>
            {intl.formatMessage({ id: cfg.labelKey, defaultMessage: status })}
          </Tag>
        ) : (
          <Tag>{status || '-'}</Tag>
        )}
        {content.workItemId ? (
          <Typography.Text type="secondary">
            #{content.workItemId}
          </Typography.Text>
        ) : null}
      </Space>
      <div style={{ fontWeight: 600 }}>{content.title ?? '-'}</div>
      {content.summary ? (
        <div style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>
          {content.summary}
        </div>
      ) : null}
      {content.workItemId ? (
        <Space style={{ marginTop: 8 }}>
          <Button size="small" type="link" onClick={onTimeline}>
            {intl.formatMessage({
              id: 'pages.im.timeline',
              defaultMessage: 'Timeline',
            })}
          </Button>
        </Space>
      ) : null}
      {canRetry || canCancel ? (
        <Space style={{ marginTop: 8 }}>
          {canRetry ? (
            <Can code={PERM.superAgentUpdate}>
              <Button size="small" type="link" onClick={onRetry}>
                {intl.formatMessage({
                  id: 'pages.im.retry',
                  defaultMessage: 'Retry',
                })}
              </Button>
            </Can>
          ) : null}
          {canCancel ? (
            <Can code={PERM.superAgentUpdate}>
              <Button size="small" type="link" danger onClick={onCancel}>
                {intl.formatMessage({
                  id: 'pages.im.cancel',
                  defaultMessage: 'Cancel',
                })}
              </Button>
            </Can>
          ) : null}
        </Space>
      ) : null}
    </div>
  );
}

interface Props {
  messages: ImMessage[];
  loading: boolean;
  hasMore: boolean;
  loadingOlder: boolean;
  bottomRef: RefObject<HTMLDivElement | null>;
  onLoadOlder: () => void;
  onReply: (id: number) => void;
  onEdit: (message: ImMessage) => void;
  onRecall: (id: number) => void;
  onRetryCard: (content: Record<string, any>) => void;
  onCancelCard: (content: Record<string, any>) => void;
  onTimeline: (content: Record<string, any>) => void;
  labels: { noMessage: string; reply: string };
}

export function MessageList({
  messages,
  loading,
  hasMore,
  loadingOlder,
  bottomRef,
  onLoadOlder,
  onReply,
  onEdit,
  onRecall,
  onRetryCard,
  onCancelCard,
  onTimeline,
  labels,
}: Props) {
  const intl = useIntl();
  return (
    <div
      style={{
        flex: 1,
        overflowY: 'auto',
        paddingRight: 8,
        maxHeight: 460,
      }}
    >
      {hasMore ? (
        <div style={{ textAlign: 'center', marginBottom: 8 }}>
          <Button size="small" loading={loadingOlder} onClick={onLoadOlder}>
            {intl.formatMessage({
              id: 'pages.im.loadOlder',
              defaultMessage: 'Load older',
            })}
          </Button>
        </div>
      ) : null}
      <Spin spinning={loading}>
        {messages.length === 0 ? (
          <Empty description={labels.noMessage} />
        ) : (
          <Listy
            items={messages}
            rowKey="id"
            itemRender={(item) => {
              const content = parsedContent(item);
              return (
                <div
                  style={{
                    display: 'block',
                    padding: '12px 0',
                    borderBottom: '1px solid #f0f0f0',
                  }}
                >
                  <Space size={8}>
                    <Tag
                      color={item.senderType === 'AGENT' ? 'blue' : 'default'}
                    >
                      {item.senderType}
                      {item.senderId ? ` #${item.senderId}` : ''}
                    </Tag>
                    <Typography.Text type="secondary">
                      #{item.seq} · {item.createdAt}
                    </Typography.Text>
                    <Button
                      type="link"
                      size="small"
                      onClick={() => onReply(item.id)}
                    >
                      {labels.reply}
                    </Button>
                    {item.senderType === 'USER' ? (
                      <>
                        {item.type !== 'CARD' &&
                        item.type !== 'IMAGE' &&
                        item.type !== 'FILE' ? (
                          <Button
                            type="link"
                            size="small"
                            onClick={() => onEdit(item)}
                          >
                            {intl.formatMessage({
                              id: 'pages.im.edit',
                              defaultMessage: 'Edit',
                            })}
                          </Button>
                        ) : null}
                        <Button
                          type="link"
                          size="small"
                          danger
                          onClick={() => onRecall(item.id)}
                        >
                          {intl.formatMessage({
                            id: 'pages.im.withdraw',
                            defaultMessage: 'Withdraw',
                          })}
                        </Button>
                      </>
                    ) : null}
                  </Space>
                  <div style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>
                    {item.type === 'CARD' ? (
                      <TaskCard
                        content={content}
                        onRetry={() => onRetryCard(content)}
                        onCancel={() => onCancelCard(content)}
                        onTimeline={() => onTimeline(content)}
                      />
                    ) : item.type === 'IMAGE' || item.type === 'FILE' ? (
                      <AttachmentMessage
                        conversationId={item.conversationId}
                        content={content}
                        type={item.type}
                      />
                    ) : (
                      messageText(item)
                    )}
                  </div>
                </div>
              );
            }}
          />
        )}
      </Spin>
      <div ref={bottomRef} />
    </div>
  );
}

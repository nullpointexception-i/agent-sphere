import { BellOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Badge, Button, Empty, List, Popover, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { TIMING } from '@/constants/timing';
import {
  type NotificationVO,
  notificationApi,
} from '@/services/agentSphere/notification';
import { tw } from '../i18n';
import { toWorkspaceSearch } from '../workspaceQuery';

const APPLICATION_RUN = 'APPLICATION_RUN';

function payloadOf(item: NotificationVO): Record<string, any> {
  if (!item.payload) return {};
  try {
    return JSON.parse(item.payload);
  } catch {
    return {};
  }
}

function runStatusLabel(status?: string) {
  if (!status) return '';
  return tw(`notification.applicationRun.status.${status}`, status);
}

export function NotificationBell({ className }: { className?: string }) {
  const [count, setCount] = useState(0);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<NotificationVO[]>([]);

  const loadCount = () => {
    notificationApi
      .unreadCount()
      .then((value) => setCount(Number(value) || 0))
      .catch(() => setCount(0));
  };

  useEffect(() => {
    loadCount();
    const timer = window.setInterval(loadCount, TIMING.NOTIFICATION_POLL_MS);
    return () => window.clearInterval(timer);
  }, []);

  const loadList = () => {
    setLoading(true);
    notificationApi
      .page({ page: 1, pageSize: 20 })
      .then((page) => setItems(page.records || []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  };

  const openList = (next: boolean) => {
    setOpen(next);
    if (next) loadList();
  };

  const readItem = async (item: NotificationVO) => {
    if (!item.read) {
      await notificationApi.markRead(item.id).catch(() => undefined);
    }
    loadCount();
    loadList();
    const payload = payloadOf(item);
    if (item.type === APPLICATION_RUN && payload.applicationId) {
      history.push(
        `/workspace${toWorkspaceSearch({
          tab: 'apps',
          appId: Number(payload.applicationId),
          sceneId: payload.sceneId ? Number(payload.sceneId) : undefined,
        })}`,
      );
      setOpen(false);
    }
  };

  const markAllRead = async () => {
    await notificationApi.markAllRead().catch(() => undefined);
    loadCount();
    loadList();
  };

  const content = (
    <div style={{ width: 320 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 8,
        }}
      >
        <strong>{tw('notifications.title', 'Notifications')}</strong>
        <Button type="link" size="small" onClick={() => void markAllRead()}>
          {tw('notifications.markAllRead', 'Mark all read')}
        </Button>
      </div>
      <Spin spinning={loading}>
        {items.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={tw('notifications.empty', 'No notifications')}
          />
        ) : (
          <List
            size="small"
            dataSource={items}
            renderItem={(item) => {
              const payload = payloadOf(item);
              const status = runStatusLabel(payload.status);
              const title = tw(
                item.titleKey || 'notifications.title',
                tw('notifications.title', 'Notifications'),
                { ...payload, status },
              );
              const body = item.bodyKey
                ? tw(item.bodyKey, status || '', { ...payload, status })
                : payload.text;
              return (
                <List.Item
                  onClick={() => void readItem(item)}
                  style={{
                    cursor: 'pointer',
                    opacity: item.read ? 0.6 : 1,
                  }}
                >
                  <List.Item.Meta
                    title={title}
                    description={body || item.createdAt}
                  />
                </List.Item>
              );
            }}
          />
        )}
      </Spin>
    </div>
  );

  return (
    <Popover
      open={open}
      onOpenChange={openList}
      trigger="click"
      content={content}
      placement="bottomRight"
    >
      <button type="button" className={className}>
        <Badge count={count} size="small">
          <BellOutlined />
        </Badge>
      </button>
    </Popover>
  );
}

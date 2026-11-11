import { Button, Empty, Space, Tag, Typography } from 'antd';
import { App } from 'antd';
import {
  type DesktopBridgeApproval,
  getDesktopBridge,
} from '@/utils/desktopBridge';
import { tw } from '../i18n';
import { useLocalApprovals } from './useLocalApprovals';

/** Desktop-runtime approvals, rendered apart from the cloud L2 inbox. */
export function LocalApprovalList() {
  const bridge = getDesktopBridge();
  const { message } = App.useApp();
  const { items, loading, reload } = useLocalApprovals();

  if (!bridge) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={tw(
          'approval.localDesktopOnly',
          'Local approvals are available in AgentSphere Desktop.',
        )}
      />
    );
  }

  const respond = async (
    item: DesktopBridgeApproval,
    decision: 'accept' | 'acceptForSession' | 'decline',
  ) => {
    try {
      await bridge.approvals.respond(item.id, decision);
      message.success(tw('approval.submitted', 'Approval submitted'));
      await reload();
    } catch (reason) {
      message.error(
        reason instanceof Error ? reason.message : String(reason),
      );
    }
  };

  if (items.length === 0) {
    return (
      <Empty
        description={tw('approval.localEmpty', 'No local approvals')}
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <Button type="text" size="small" loading={loading} onClick={() => void reload()}>
        {tw('common.refresh', 'Refresh')}
      </Button>
      {items.map((item) => (
        <div
          key={item.id}
          style={{
            border: '1px solid #e5e7eb',
            borderRadius: 10,
            padding: 14,
            background: '#fff',
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              marginBottom: 8,
            }}
          >
            <Typography.Text strong>{item.title}</Typography.Text>
            <Tag color="geekblue">{item.category}</Tag>
            <Tag>LOCAL</Tag>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {new Date(item.createdAt).toLocaleString()}
            </Typography.Text>
          </div>
          {item.command ? (
            <pre
              style={{
                margin: '0 0 8px',
                background: '#f7f7f8',
                borderRadius: 6,
                padding: 8,
                fontSize: 11,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              {item.command}
            </pre>
          ) : null}
          <Typography.Paragraph
            type="secondary"
            ellipsis={{ rows: 3, expandable: true }}
            style={{ fontSize: 12, marginBottom: 10 }}
          >
            {item.detail}
          </Typography.Paragraph>
          <Space wrap>
            <Button
              size="small"
              type="primary"
              onClick={() => void respond(item, 'accept')}
            >
              {tw('approval.once', 'Allow once')}
            </Button>
            <Button
              size="small"
              onClick={() => void respond(item, 'acceptForSession')}
            >
              {tw('approval.session', 'Allow for this session')}
            </Button>
            <Button
              size="small"
              danger
              onClick={() => void respond(item, 'decline')}
            >
              {tw('approval.deny', 'Do not allow')}
            </Button>
          </Space>
        </div>
      ))}
    </div>
  );
}

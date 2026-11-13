import { CheckCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  Divider,
  Drawer,
  Empty,
  Popconfirm,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useState } from 'react';
import type {
  ApprovalDecision,
  PendingApproval,
} from '@/services/agentSphere/superAgent';
import { superAgentApi } from '@/services/agentSphere/superAgent';
import { tw } from '../i18n';
import {
  approvalSourceLabel,
  optionsForScope,
  parseApprovalCalls,
  prettyArguments,
} from './approvalModel';
import { LocalApprovalList } from './LocalApprovalList';
import { useLocalApprovals } from './useLocalApprovals';

interface Props {
  open: boolean;
  items: PendingApproval[];
  loading: boolean;
  onClose: () => void;
  onReload: () => void;
}

function decisionLabel(decision: string) {
  switch (decision) {
    case 'DENY':
      return tw('approval.deny', 'Do not allow');
    case 'ALLOW_ONCE':
      return tw('approval.once', 'Allow once');
    case 'ALLOW_RUN':
      return tw('approval.run', 'Allow for this run');
    case 'ALLOW_SESSION':
      return tw('approval.session', 'Allow for this session');
    default:
      return decision;
  }
}

export function ApprovalInboxDrawer({
  open,
  items,
  loading,
  onClose,
  onReload,
}: Props) {
  const { message } = App.useApp();
  const [decisions, setDecisions] = useState<
    Record<string, Record<string, string>>
  >({});
  const [submitting, setSubmitting] = useState<string | null>(null);
  const _local = useLocalApprovals();

  const pick = (approvalId: string, callId: string, decision: string) => {
    setDecisions((previous) => ({
      ...previous,
      [approvalId]: {
        ...(previous[approvalId] ?? {}),
        [callId]: decision,
      },
    }));
  };

  const submit = async (
    item: PendingApproval,
    override?: Record<string, string>,
  ) => {
    const calls = parseApprovalCalls(item.toolCallsJson);
    const picked = override ?? decisions[item.approvalId] ?? {};
    if (calls.some((call) => !picked[call.callId])) {
      return;
    }
    const payload: ApprovalDecision[] = calls.map((call) => ({
      callId: call.callId,
      decision: picked[call.callId],
    }));
    setSubmitting(item.approvalId);
    try {
      await superAgentApi.resolveApproval(item.approvalId, payload);
      message.success(tw('approval.submitted', 'Approval submitted'));
      setDecisions((previous) => {
        const next = { ...previous };
        delete next[item.approvalId];
        return next;
      });
      onReload();
    } catch {
      message.error(tw('approval.submitFailed', 'Failed to submit approval'));
    } finally {
      setSubmitting(null);
    }
  };

  const denyAll = (item: PendingApproval) => {
    const calls = parseApprovalCalls(item.toolCallsJson);
    const all: Record<string, string> = {};
    calls.forEach((call) => {
      all[call.callId] = 'DENY';
    });
    void submit(item, all);
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={560}
      title={
        <Space size={8}>
          <span>{tw('approval.title', 'Pending approvals')}</span>
          <Tag color="orange">L2</Tag>
          <Tag>{items.length}</Tag>
        </Space>
      }
      extra={
        <Button
          type="text"
          icon={<ReloadOutlined />}
          loading={loading}
          onClick={onReload}
        >
          {tw('common.refresh', 'Refresh')}
        </Button>
      }
    >
      <Typography.Title level={5} style={{ marginTop: 0 }}>
        {tw('approval.cloud', 'Cloud approvals')}
      </Typography.Title>
      {items.length === 0 ? (
        <Empty
          description={tw('approval.empty', 'No pending approvals')}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {items.map((item) => {
            const calls = parseApprovalCalls(item.toolCallsJson);
            const picked = decisions[item.approvalId] ?? {};
            const ready =
              calls.length > 0 && calls.every((c) => picked[c.callId]);
            return (
              <div
                key={item.approvalId}
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
                  <Typography.Text strong>
                    {item.agentName || tw('approval.unknownAgent', 'Avatar')}
                  </Typography.Text>
                  <Tag
                    color={item.sessionType === 'WORKER' ? 'blue' : 'purple'}
                  >
                    {approvalSourceLabel(item.sessionType)}
                  </Tag>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {item.createdAt}
                  </Typography.Text>
                </div>
                {item.sessionTitle ? (
                  <Typography.Paragraph
                    type="secondary"
                    ellipsis={{ rows: 1 }}
                    style={{ marginBottom: 10, fontSize: 12 }}
                  >
                    {item.sessionTitle}
                  </Typography.Paragraph>
                ) : null}

                {calls.map((call) => (
                  <div
                    key={call.callId}
                    style={{
                      borderTop: '1px dashed #eee',
                      paddingTop: 10,
                      marginTop: 10,
                    }}
                  >
                    <div style={{ marginBottom: 6 }}>
                      <Typography.Text strong>
                        {call.displayName || call.toolName || call.callId}
                      </Typography.Text>
                      {call.action ? (
                        <Typography.Text type="secondary">
                          {' '}
                          · {call.action}
                        </Typography.Text>
                      ) : null}
                      <Tag style={{ marginLeft: 8 }} color="orange">
                        {call.approvalScope || 'ONCE'}
                      </Tag>
                    </div>
                    <details>
                      <summary
                        style={{
                          cursor: 'pointer',
                          fontSize: 12,
                          color: '#888',
                        }}
                      >
                        {tw('approval.arguments', 'Arguments')}
                      </summary>
                      <pre
                        style={{
                          margin: '8px 0 0',
                          maxHeight: 160,
                          overflow: 'auto',
                          background: '#f7f7f8',
                          borderRadius: 6,
                          padding: 8,
                          fontSize: 11,
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-word',
                        }}
                      >
                        {prettyArguments(call.arguments)}
                      </pre>
                    </details>
                    <Space wrap style={{ marginTop: 8 }}>
                      {optionsForScope(call.approvalScope).map((option) => (
                        <Button
                          key={option}
                          size="small"
                          danger={option === 'DENY'}
                          type={
                            picked[call.callId] === option
                              ? 'primary'
                              : 'default'
                          }
                          onClick={() =>
                            pick(item.approvalId, call.callId, option)
                          }
                        >
                          {decisionLabel(option)}
                        </Button>
                      ))}
                    </Space>
                  </div>
                ))}

                {calls.length === 0 ? (
                  <Typography.Text type="secondary">
                    {tw(
                      'approval.callsMissing',
                      'Tool call details are unavailable; handle it in the console.',
                    )}
                  </Typography.Text>
                ) : (
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'flex-end',
                      gap: 8,
                      marginTop: 14,
                    }}
                  >
                    <Popconfirm
                      title={tw(
                        'approval.denyAllConfirm',
                        'Deny all tool calls in this batch?',
                      )}
                      onConfirm={() => denyAll(item)}
                    >
                      <Button danger size="small" disabled={submitting != null}>
                        {tw('approval.denyAll', 'Deny all')}
                      </Button>
                    </Popconfirm>
                    <Button
                      type="primary"
                      size="small"
                      icon={<CheckCircleOutlined />}
                      disabled={!ready || submitting != null}
                      loading={submitting === item.approvalId}
                      onClick={() => void submit(item)}
                    >
                      {tw('approval.submit', 'Submit')}
                    </Button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
      <Divider />
      <Typography.Title level={5}>
        {tw('approval.local', 'Local approvals')}
      </Typography.Title>
      <LocalApprovalList />
    </Drawer>
  );
}

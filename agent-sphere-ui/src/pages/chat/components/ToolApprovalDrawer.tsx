import { useIntl } from '@umijs/max';
import { Button, Popconfirm, Space, Tabs, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import ChatBottomSheet from './ChatBottomSheet';

export interface ToolApprovalCall {
  callId: string;
  toolName: string;
  displayName?: string;
  action?: string;
  arguments?: string;
  approvalScope?: 'ONCE' | 'RUN' | 'SESSION';
}

export interface ToolApprovalBatch {
  approvalId: string;
  runId: number;
  calls: ToolApprovalCall[];
}

interface Props {
  open: boolean;
  batch: ToolApprovalBatch | null;
  submitting?: boolean;
  bottomOffset?: number;
  onResolve: (decisions: Array<{ callId: string; decision: string }>) => void;
}

const DECISIONS = ['DENY', 'ALLOW_ONCE', 'ALLOW_RUN', 'ALLOW_SESSION'] as const;

function optionsFor(scope?: string) {
  if (scope === 'SESSION') return DECISIONS;
  if (scope === 'RUN') return ['DENY', 'ALLOW_ONCE', 'ALLOW_RUN'] as const;
  return ['DENY', 'ALLOW_ONCE'] as const;
}

function prettyArgs(raw?: string) {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

export default function ToolApprovalDrawer({
  open,
  batch,
  submitting,
  bottomOffset = 0,
  onResolve,
}: Props) {
  const intl = useIntl();
  const [decisions, setDecisions] = useState<Record<string, string>>({});

  useEffect(() => {
    setDecisions({});
  }, [batch?.approvalId]);

  const calls = batch?.calls || [];
  const allSelected =
    calls.length > 0 && calls.every((c) => Boolean(decisions[c.callId]));

  const decisionLabel = (decision: string) => {
    switch (decision) {
      case 'DENY':
        return intl.formatMessage({
          id: 'pages.chat.toolApproval.deny',
          defaultMessage: 'Do not allow',
        });
      case 'ALLOW_ONCE':
        return intl.formatMessage({
          id: 'pages.chat.toolApproval.once',
          defaultMessage: 'Allow once',
        });
      case 'ALLOW_RUN':
        return intl.formatMessage({
          id: 'pages.chat.toolApproval.run',
          defaultMessage: 'Allow for this message',
        });
      default:
        return intl.formatMessage({
          id: 'pages.chat.toolApproval.session',
          defaultMessage: 'Allow for this session',
        });
    }
  };

  const scopeLabel = (scope?: string) => {
    if (scope === 'SESSION')
      return intl.formatMessage({
        id: 'pages.chat.toolApproval.scopeSession',
        defaultMessage: 'session',
      });
    if (scope === 'RUN')
      return intl.formatMessage({
        id: 'pages.chat.toolApproval.scopeRun',
        defaultMessage: 'this message',
      });
    return intl.formatMessage({
      id: 'pages.chat.toolApproval.scopeOnce',
      defaultMessage: 'once',
    });
  };

  const items = useMemo(
    () =>
      calls.map((call, index) => {
        const selected = decisions[call.callId];
        return {
          key: call.callId,
          label: (
            <Space size={4}>
              <span>{call.displayName || call.toolName}</span>
              {selected ? <Tag color="blue">{decisionLabel(selected)}</Tag> : null}
            </Space>
          ),
          children: (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div>
                <Typography.Text strong>
                  {call.displayName || call.toolName}
                </Typography.Text>
                {call.action ? (
                  <Typography.Text type="secondary">
                    {' '}
                    · {call.action}
                  </Typography.Text>
                ) : null}
                <Tag style={{ marginLeft: 8 }} color="orange">
                  {intl.formatMessage({
                    id: 'pages.chat.toolApproval.maxScope',
                    defaultMessage: 'Max scope',
                  })}
                  : {scopeLabel(call.approvalScope)}
                </Tag>
              </div>
              <details>
                <summary style={{ cursor: 'pointer', fontSize: 12 }}>
                  {intl.formatMessage({
                    id: 'pages.chat.toolApproval.arguments',
                    defaultMessage: 'Arguments',
                  })}
                </summary>
                <pre
                  style={{
                    margin: '8px 0 0',
                    maxHeight: 180,
                    overflow: 'auto',
                    background: '#f5f5f5',
                    borderRadius: 6,
                    padding: 10,
                    fontSize: 12,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }}
                >
                  {prettyArgs(call.arguments)}
                </pre>
              </details>
              <Space wrap>
                {optionsFor(call.approvalScope).map((decision) => (
                  <Button
                    key={decision}
                    danger={decision === 'DENY'}
                    type={selected === decision ? 'primary' : 'default'}
                    onClick={() =>
                      setDecisions((prev) => ({
                        ...prev,
                        [call.callId]: decision,
                      }))
                    }
                  >
                    {decisionLabel(decision)}
                  </Button>
                ))}
              </Space>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {index + 1} / {calls.length}
              </Typography.Text>
            </div>
          ),
        };
      }),
    [calls, decisions, intl],
  );

  const submit = () => {
    if (!allSelected) return;
    onResolve(
      calls.map((c) => ({ callId: c.callId, decision: decisions[c.callId] })),
    );
  };

  const denyAll = () => {
    const all: Record<string, string> = {};
    calls.forEach((c) => {
      all[c.callId] = 'DENY';
    });
    setDecisions(all);
    onResolve(calls.map((c) => ({ callId: c.callId, decision: 'DENY' })));
  };

  return (
    <ChatBottomSheet
      open={open}
      bottomOffset={bottomOffset}
      title={
        <Space size={8}>
          <Typography.Text strong>
            {intl.formatMessage({
              id: 'pages.chat.toolApproval.title',
              defaultMessage: 'Tool approval required',
            })}
          </Typography.Text>
          <Tag color="orange">L2</Tag>
          <Tag>
            {intl.formatMessage(
              {
                id: 'pages.chat.toolApproval.selected',
                defaultMessage: '{selected}/{total} selected',
              },
              { selected: Object.keys(decisions).length, total: calls.length },
            )}
          </Tag>
        </Space>
      }
      footer={
        <>
          <Popconfirm
            title={intl.formatMessage({
              id: 'pages.chat.toolApproval.denyAllConfirm',
              defaultMessage: 'Deny all tool calls in this batch?',
            })}
            onConfirm={denyAll}
          >
            <Button danger disabled={submitting || calls.length === 0}>
              {intl.formatMessage({
                id: 'pages.chat.toolApproval.denyAll',
                defaultMessage: 'Deny all',
              })}
            </Button>
          </Popconfirm>
          <Button
            type="primary"
            loading={submitting}
            disabled={!allSelected}
            onClick={submit}
          >
            {intl.formatMessage({
              id: 'pages.chat.toolApproval.submit',
              defaultMessage: 'Submit',
            })}
          </Button>
        </>
      }
    >
      <Tabs items={items} />
    </ChatBottomSheet>
  );
}

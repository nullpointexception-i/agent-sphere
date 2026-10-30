import {
  BarChartOutlined,
  CheckSquareOutlined,
  SafetyCertificateOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { Divider, Empty, Tag } from 'antd';
import { cacheHitRate, contextUsagePercent, formatTokens } from '../Usage';

interface TodoItem {
  content: string;
  status: string;
  priority: string;
  skillId?: number | null;
  stepId?: string | null;
}

interface AuthorizedTool {
  toolRef?: string;
  displayName?: string;
  securityLevel?: string;
  scope?: string;
}

interface ToolStats {
  sessionToolCallCount?: number;
  currentRunToolCallCount?: number;
  latestRunToolCallCount?: number;
  authorizedTools?: AuthorizedTool[];
}

interface SessionPanelProps {
  open: boolean;
  onClose: () => void;
  todos: TodoItem[];
  toolStats?: ToolStats | null;
  sessionUsage?: any;
  maxInputTokens?: number | null;
}

const statusIcon = (s: string) => {
  switch (s) {
    case 'completed':
      return '✅';
    case 'in_progress':
      return '⏳';
    case 'cancelled':
      return '❌';
    case 'started':
      return '⏳';
    case 'succeeded':
      return '✅';
    case 'failed':
      return '❌';
    default:
      return '⬜';
  }
};

function StatRow({ label, value }: { label: string; value: number }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '3px 0', fontSize: 12 }}>
      <span style={{ color: '#8c8c8c' }}>{label}</span>
      <span style={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>{value}</span>
    </div>
  );
}

function UsageRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '2px 0', fontSize: 12 }}>
      <span style={{ color: '#8c8c8c' }}>{label}</span>
      <span style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</span>
    </div>
  );
}

export default function SessionPanel({
  open,
  onClose,
  todos,
  toolStats,
  sessionUsage,
  maxInputTokens,
}: SessionPanelProps) {
  const intl = useIntl();
  if (!open) return null;

  const stats = toolStats || {};
  const authorizedTools = stats.authorizedTools || [];
  const cacheRate = cacheHitRate(sessionUsage);
  const contextPercent = contextUsagePercent(
    sessionUsage?.latestPromptTokens,
    maxInputTokens,
  );
  const scopeLabel = (scope?: string) => {
    if (scope === 'SESSION') {
      return intl.formatMessage({
        id: 'pages.chat.sessionPanel.scopeSession',
        defaultMessage: 'This session',
      });
    }
    return intl.formatMessage({
      id: 'pages.chat.sessionPanel.scopeRun',
      defaultMessage: 'This message',
    });
  };

  return (
    <div
      style={{
        width: 300,
        flexShrink: 0,
        borderLeft: '1px solid #e8e8e8',
        padding: '0 16px',
        background: '#fafafa',
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '12px 0',
          flexShrink: 0,
        }}
      >
        <strong style={{ fontSize: 14 }}>
          {intl.formatMessage({
            id: 'pages.chat.sessionPanel',
            defaultMessage: 'Session Panel',
          })}
        </strong>
        <a onClick={onClose} style={{ fontSize: 14, color: '#999', cursor: 'pointer' }}>
          ✕
        </a>
      </div>

      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div
          style={{
            fontWeight: 500,
            fontSize: 13,
            padding: '6px 0',
            flexShrink: 0,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
        >
          <CheckSquareOutlined />
          <span>
            {intl.formatMessage({
              id: 'pages.chat.todoList',
              defaultMessage: 'Todo List',
            })}{' '}
            ({todos.length})
          </span>
        </div>
        <div style={{ flex: 1, overflowY: 'auto' }}>
          {todos.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={intl.formatMessage({
                id: 'pages.chat.todoList.empty',
                defaultMessage: 'No todo items yet',
              })}
              style={{ fontSize: 12, margin: '16px 0' }}
            />
          ) : (
            todos.map((t, i) => (
              <div
                key={i}
                style={{
                  padding: '4px 0',
                  fontSize: 13,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <span>{statusIcon(t.status)}</span>
                <span
                  style={{
                    textDecoration: t.status === 'cancelled' ? 'line-through' : undefined,
                    opacity: t.status === 'cancelled' ? 0.5 : 1,
                    flex: 1,
                  }}
                >
                  {t.content}
                </span>
                {(t.skillId != null || t.stepId) && (
                  <Tag color="purple" style={{ fontSize: 10, marginLeft: 4, lineHeight: '16px' }}>
                    {t.skillId != null ? `skill#${t.skillId}` : ''}
                    {t.stepId ? ` ${t.stepId}` : ''}
                  </Tag>
                )}
                <Tag
                  style={{ fontSize: 10, marginLeft: 4, lineHeight: '16px' }}
                  color={t.priority === 'high' ? 'red' : t.priority === 'medium' ? 'orange' : 'blue'}
                >
                  {t.priority}
                </Tag>
              </div>
            ))
          )}
        </div>
      </div>

      <Divider style={{ margin: '4px 0', flexShrink: 0 }} />

      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div
          style={{
            fontWeight: 500,
            fontSize: 13,
            padding: '6px 0',
            flexShrink: 0,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
        >
          <BarChartOutlined />
          <span>
            {intl.formatMessage({
              id: 'pages.chat.sessionPanel.overview',
              defaultMessage: 'Run overview',
            })}
          </span>
        </div>
        <div style={{ flex: 1, overflowY: 'auto' }}>
          <StatRow
            label={intl.formatMessage({
              id: 'pages.chat.sessionPanel.sessionToolCalls',
              defaultMessage: 'Session tool calls',
            })}
            value={stats.sessionToolCallCount || 0}
          />
          <StatRow
            label={intl.formatMessage({
              id: 'pages.chat.sessionPanel.currentRunToolCalls',
              defaultMessage: 'Current run tool calls',
            })}
            value={stats.currentRunToolCallCount || 0}
          />
          <StatRow
            label={intl.formatMessage({
              id: 'pages.chat.sessionPanel.latestRunToolCalls',
              defaultMessage: 'Last run tool calls',
            })}
            value={stats.latestRunToolCallCount || 0}
          />

          <Divider style={{ margin: '10px 0 6px' }} />
          <div style={{ fontSize: 12, fontWeight: 600, color: '#8c8c8c', marginBottom: 4 }}>
            {intl.formatMessage({
              id: 'pages.chat.sessionPanel.sessionUsage',
              defaultMessage: 'Session usage',
            })}
          </div>
          <UsageRow label="Prompt" value={formatTokens(sessionUsage?.promptTokens)} />
          <UsageRow label="Completion" value={formatTokens(sessionUsage?.completionTokens)} />
          <UsageRow label="Total" value={formatTokens(sessionUsage?.totalTokens)} />
          <UsageRow
            label="Cache"
            value={cacheRate != null ? `${cacheRate}%` : '-'}
          />
          <UsageRow
            label={intl.formatMessage({
              id: 'pages.chat.sessionPanel.currentContext',
              defaultMessage: 'Current context',
            })}
            value={contextPercent != null ? `${contextPercent}%` : '-'}
          />

          <Divider style={{ margin: '10px 0 6px' }} />
          <div
            style={{
              fontSize: 12,
              fontWeight: 600,
              color: '#8c8c8c',
              marginBottom: 4,
              display: 'flex',
              alignItems: 'center',
              gap: 4,
            }}
          >
            <SafetyCertificateOutlined />
            {intl.formatMessage({
              id: 'pages.chat.sessionPanel.authorizedTools',
              defaultMessage: 'Authorized tools',
            })}
          </div>
          {authorizedTools.length === 0 ? (
            <div style={{ fontSize: 12, color: '#aaa', padding: '4px 0' }}>
              {intl.formatMessage({
                id: 'pages.chat.sessionPanel.noAuthorizedTools',
                defaultMessage: 'No persistent authorizations',
              })}
            </div>
          ) : (
            authorizedTools.map((tool, i) => (
              <div
                key={`${tool.toolRef}-${tool.scope}-${i}`}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '4px 0',
                  fontSize: 12,
                }}
              >
                <ToolOutlined style={{ color: '#8c8c8c' }} />
                <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {tool.displayName || tool.toolRef}
                </span>
                <Tag style={{ margin: 0, fontSize: 10, lineHeight: '16px' }} color="orange">
                  {tool.securityLevel || 'L2'}
                </Tag>
                <Tag style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}>
                  {scopeLabel(tool.scope)}
                </Tag>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

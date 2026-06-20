import { Tag, Empty, Divider, Tooltip } from 'antd';
import { CheckSquareOutlined, ToolOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';

interface TodoItem {
  content: string;
  status: string;
  priority: string;
}

interface ToolCallItem {
  name: string;
  status: string;
  displayNameCn?: string;
  displayNameEn?: string;
  _publishId?: string;
  _runId?: number;
  ts?: number;
}

interface SessionPanelProps {
  open: boolean;
  onClose: () => void;
  todos: TodoItem[];
  toolCalls: ToolCallItem[];
  messages: any[];
  runUserMessages: Map<number, string>;
}

const statusIcon = (s: string) => {
  switch (s) {
    case 'completed': return '✅';
    case 'in_progress': return '⏳';
    case 'cancelled': return '❌';
    case 'started': return '⏳';
    case 'succeeded': return '✅';
    case 'failed': return '❌';
    default: return '⬜';
  }
};

function groupByRunId(items: ToolCallItem[]): Record<string, ToolCallItem[]> {
  const groups: Record<string, ToolCallItem[]> = {};
  for (const item of items) {
    const key = item._runId != null ? String(item._runId) : 'unknown';
    if (!groups[key]) groups[key] = [];
    groups[key].push(item);
  }
  return groups;
}

function userMessageLabel(runUserMessages: Map<number, string>, runId: number): string {
  const msg = runUserMessages.get(runId);
  if (!msg) return `Run #${runId}`;
  const raw = (msg || '').replace(/^[\s\u{1F300}-\u{1FFFF}▶️✅❌🔄]+/u, '').trim();
  return raw.length > 10 ? `「${raw.slice(0, 10)}…」` : `「${raw}」`;
}

export default function SessionPanel({ open, onClose, todos, toolCalls, runUserMessages }: SessionPanelProps) {
  const intl = useIntl();
  if (!open) return null;

  const locale = intl.locale;
  const toolLabel = (tc: ToolCallItem) =>
    locale === 'en-US' ? (tc.displayNameEn || tc.name) : (tc.displayNameCn || tc.name);

  const toolGroups = groupByRunId(toolCalls);
  const sortedRunIds = Object.keys(toolGroups).sort((a, b) => Number(b) - Number(a));

  return (
    <div
      style={{
        width: 280,
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
          {intl.formatMessage({ id: 'pages.chat.sessionPanel', defaultMessage: 'Session Panel' })}
        </strong>
        <a onClick={onClose} style={{ fontSize: 14, color: '#999', cursor: 'pointer' }}>
          ✕
        </a>
      </div>

      {/* Todo List */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div style={{ fontWeight: 500, fontSize: 13, padding: '6px 0', flexShrink: 0, display: 'flex', alignItems: 'center', gap: 6 }}>
          <CheckSquareOutlined />
          <span>
            {intl.formatMessage({ id: 'pages.chat.todoList', defaultMessage: 'Todo List' })} ({todos.length})
          </span>
        </div>
        <div style={{ flex: 1, overflowY: 'auto' }}>
          {todos.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={intl.formatMessage({ id: 'pages.chat.todoList.empty', defaultMessage: 'No todo items yet' })}
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
                <Tag style={{ fontSize: 10, marginLeft: 4, lineHeight: '16px' }} color={t.priority === 'high' ? 'red' : t.priority === 'medium' ? 'orange' : 'blue'}>
                  {t.priority}
                </Tag>
              </div>
            ))
          )}
        </div>
      </div>

      <Divider style={{ margin: '4px 0', flexShrink: 0 }} />

      {/* Tool Calls */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div style={{ fontWeight: 500, fontSize: 13, padding: '6px 0', flexShrink: 0, display: 'flex', alignItems: 'center', gap: 6 }}>
          <ToolOutlined />
          <span>
            {intl.formatMessage({ id: 'pages.chat.toolCalls', defaultMessage: 'Tool Calls' })} ({toolCalls.length})
          </span>
        </div>
        <div style={{ flex: 1, overflowY: 'auto' }}>
          {toolCalls.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={intl.formatMessage({ id: 'pages.chat.toolCalls.empty', defaultMessage: 'No tool calls yet' })}
              style={{ fontSize: 12, margin: '16px 0' }}
            />
          ) : (
            sortedRunIds.map((runId) => {
              const label = userMessageLabel(runUserMessages, Number(runId));
              return (
                <div key={runId}>
                  <div style={{ fontSize: 11, color: '#bbb', padding: '4px 0', fontWeight: 500 }}>
                    {label}
                  </div>
                  {[...toolGroups[runId]].reverse().map((tc, i) => (
                    <div
                      key={tc._publishId || i}
                      style={{
                        padding: '3px 0',
                        fontSize: 13,
                        display: 'flex',
                        alignItems: 'center',
                        gap: 6,
                      }}
                    >
                      <span>{statusIcon(tc.status)}</span>
                      <Tooltip title={tc.name}>
                        <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', cursor: 'default' }}>
                          {toolLabel(tc)}
                        </span>
                      </Tooltip>
                      <Tag
                        style={{ fontSize: 10, lineHeight: '16px', flexShrink: 0 }}
                        color={
                          tc.status === 'succeeded'
                            ? 'green'
                            : tc.status === 'failed'
                              ? 'red'
                              : tc.status === 'started'
                                ? 'blue'
                                : 'orange'
                        }
                      >
                        {tc.status}
                      </Tag>
                    </div>
                  ))}
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}

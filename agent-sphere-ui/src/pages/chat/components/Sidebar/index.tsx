import { App, Button, Input, Modal } from 'antd';
import { Conversations } from '@ant-design/x';
import { CheckOutlined, CheckSquareOutlined, DeleteOutlined, EditOutlined, EyeOutlined, PlusOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { useMemo, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { formatTime } from '@/utils/format';
import { useStyles } from '../../style';
import RunDrawer from '../RunDrawer';

interface SidebarProps {
  sessions: any[];
  currentSession: any;
  hasMoreSessions: boolean;
  onLoadMore: () => void;
  onSelect: (id: number) => void;
  onDelete: (id: number) => void;
  onRename: (id: number, title: string) => void;
  onBatchDelete: (ids: number[]) => void;
  onNewChat: () => void;
}

export default function Sidebar({
  sessions, currentSession, hasMoreSessions,
  onLoadMore, onSelect, onDelete, onRename, onBatchDelete, onNewChat,
}: SidebarProps) {
  const intl = useIntl();
  const { message, modal } = App.useApp();
  const { styles } = useStyles();

  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [editingValue, setEditingValue] = useState('');
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [viewSession, setViewSession] = useState<any>(null);
  const [runDrawerSessionId, setRunDrawerSessionId] = useState<number | null>(null);
  const [summaryOpen, setSummaryOpen] = useState(false);
  const [summarySessionId, setSummarySessionId] = useState<number | null>(null);
  const [summaryEditValue, setSummaryEditValue] = useState('');
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [summarySaving, setSummarySaving] = useState(false);

  const handleShowSummary = async (sessionId: number) => {
    setSummaryLoading(true);
    setSummarySessionId(sessionId);
    setSummaryOpen(true);
    try {
      const s = await agentApi.sessions.get(sessionId);
      setSummaryEditValue(s.summary || '');
    } finally {
      setSummaryLoading(false);
    }
  };

  const handleSaveSummary = async () => {
    if (!summarySessionId) return;
    setSummarySaving(true);
    try {
      await agentApi.sessions.updateSummary(summarySessionId, summaryEditValue);
      message.success(intl.formatMessage({ id: 'pages.chat.saveSummary', defaultMessage: 'Save Summary' }));
    } catch {
      message.error(intl.formatMessage({ id: 'pages.chat.saveFailed', defaultMessage: 'Save failed' }));
    } finally {
      setSummarySaving(false);
    }
  };

  const conversationsItems = useMemo(() =>
    sessions.map((s: any) => ({
      key: String(s.id),
      label: editingKey === String(s.id) ? (
        <Input
          size="small"
          value={editingValue}
          onChange={(e) => setEditingValue(e.target.value)}
          onPressEnter={async () => {
            if (editingValue.trim()) {
              await onRename(s.id, editingValue.trim());
              message.success(intl.formatMessage({ id: 'pages.chat.renamed', defaultMessage: 'Renamed' }));
            }
            setEditingKey(null);
          }}
          onBlur={() => setEditingKey(null)}
          autoFocus
          onClick={(e) => e.stopPropagation()}
          maxLength={255}
          suffix={
            <CheckOutlined
              style={{ cursor: 'pointer', color: '#52c41a' }}
              onMouseDown={(e) => e.preventDefault()}
              onClick={async (e) => {
                e.stopPropagation();
                if (editingValue.trim()) {
                  await onRename(s.id, editingValue.trim());
                  message.success(intl.formatMessage({ id: 'pages.chat.renamed', defaultMessage: 'Renamed' }));
                }
                setEditingKey(null);
              }}
            />
          }
          style={{ height: 28 }}
        />
      ) : (
        <span
          className={styles.convItem}
          onClick={selectMode ? (e) => {
            e.stopPropagation();
            setSelectedIds((prev) => {
              const next = new Set(prev);
              if (next.has(s.id)) next.delete(s.id);
              else next.add(s.id);
              return next;
            });
          } : undefined}
        >
          {selectMode && (
            <span style={{ marginRight: 8, fontSize: 16, cursor: 'pointer', color: selectedIds.has(s.id) ? '#1677ff' : undefined }}>
              {selectedIds.has(s.id) ? '✓' : '○'}
            </span>
          )}
          <span className="conv-label" title={s.title}>{s.title}</span>
          <span className="conv-actions">
            <EyeOutlined style={{ fontSize: 13, cursor: 'pointer' }} onClick={(e) => { e.stopPropagation(); setViewSession(s); }} />
            <EditOutlined style={{ fontSize: 13, cursor: 'pointer' }} onClick={(e) => { e.stopPropagation(); setEditingValue(s.title); setEditingKey(String(s.id)); }} />
            <UnorderedListOutlined style={{ fontSize: 13, cursor: 'pointer' }} onClick={(e) => { e.stopPropagation(); setRunDrawerSessionId(s.id); }} />
            <span style={{ fontSize: 13, cursor: 'pointer', userSelect: 'none' }} onClick={(e) => { e.stopPropagation(); handleShowSummary(s.id); }}>🧠</span>
            <DeleteOutlined style={{ fontSize: 13, cursor: 'pointer' }} className="delete-icon" onClick={(e) => {
              e.stopPropagation();
              modal.confirm({
                title: intl.formatMessage({ id: 'pages.deleteConfirm.title', defaultMessage: 'Delete {name}' }, { name: 'session' }),
                content: intl.formatMessage({ id: 'pages.deleteConfirm.content', defaultMessage: 'Are you sure you want to delete this {name}?' }, { name: 'session' }),
                okType: 'danger',
                onOk: () => onDelete(s.id),
              });
            }} />
          </span>
        </span>
      ),
    })),
    [sessions, editingKey, editingValue, selectMode, selectedIds, onRename, onDelete, message, intl, styles.convItem],
  );

  return (
    <>
      <div className={styles.sidebarNewChat}>
        <div style={{ display: 'flex', flexDirection: 'column', width: '100%', gap: 8 }}>
          <div className={styles.sidebarNewChatItem} style={{ display: 'flex', alignItems: 'center', gap: 6 }} onClick={onNewChat}>
            <PlusOutlined />
            {intl.formatMessage({ id: 'pages.chat.newChat', defaultMessage: 'New Chat' })}
          </div>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <span className={styles.sidebarNewChatItem} style={{ color: selectMode ? '#1677ff' : undefined }} onClick={() => !selectMode && setSelectMode(true)}>
              <CheckSquareOutlined style={{ marginRight: 4 }} />
              {intl.formatMessage({ id: 'pages.chat.batchOperation', defaultMessage: 'Batch operation' })}
            </span>
            {selectMode && (
              <div style={{ display: 'flex', gap: 12, marginLeft: 'auto' }}>
                <span className={styles.sidebarNewChatItem}
                  style={{ color: selectedIds.size === 0 ? undefined : '#ff4d4f', cursor: selectedIds.size === 0 ? 'not-allowed' : 'pointer' }}
                  onClick={() => {
                    if (selectedIds.size === 0) return;
                    modal.confirm({
                      title: intl.formatMessage({ id: 'pages.deleteConfirm.title', defaultMessage: 'Delete {name}' }, { name: `${selectedIds.size} sessions` }),
                      content: intl.formatMessage({ id: 'pages.deleteConfirm.content', defaultMessage: 'Are you sure you want to delete this {name}?' }, { name: 'session' }),
                      okType: 'danger',
                      onOk: async () => {
                        await onBatchDelete(Array.from(selectedIds));
                        setSelectMode(false);
                        setSelectedIds(new Set());
                      },
                    });
                  }}
                >
                  {intl.formatMessage({ id: 'pages.chat.deleteSelected', defaultMessage: 'Delete' })} ({selectedIds.size})
                </span>
                <span className={styles.sidebarNewChatItem} onClick={() => { setSelectMode(false); setSelectedIds(new Set()); }}>
                  {intl.formatMessage({ id: 'pages.chat.cancel', defaultMessage: 'Cancel' })}
                </span>
              </div>
            )}
          </div>
        </div>
      </div>
      <div className={styles.sidebarScroll}>
        <Conversations
          items={conversationsItems}
          activeKey={currentSession ? String(currentSession.id) : ''}
          onActiveChange={(key) => { if (!selectMode) onSelect(Number(key)); }}
        />
        {hasMoreSessions && (
          <div className={styles.sidebarLoadMore}>
            <Button type="link" size="small" onClick={onLoadMore}>
              {intl.formatMessage({ id: 'chat.loadMoreSessions', defaultMessage: 'Load more sessions' })}
            </Button>
          </div>
        )}
      </div>
      <Modal
        title={intl.formatMessage({ id: 'pages.chat.sessionDetail', defaultMessage: 'Session Detail' })}
        open={!!viewSession}
        onCancel={() => setViewSession(null)}
        footer={null}
        width={560}
      >
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <tbody>
            {[
              { label: intl.formatMessage({ id: 'pages.table.id' }), value: viewSession?.id },
              { label: intl.formatMessage({ id: 'pages.chat.title', defaultMessage: 'Title' }), value: viewSession?.title || '-' },
              { label: intl.formatMessage({ id: 'pages.table.createdBy', defaultMessage: 'Created By' }), value: viewSession?.createdBy || '-' },
              { label: intl.formatMessage({ id: 'pages.table.created' }), value: formatTime(viewSession?.createdAt) },
              { label: intl.formatMessage({ id: 'pages.table.updatedBy', defaultMessage: 'Updated By' }), value: viewSession?.updatedBy || '-' },
              { label: intl.formatMessage({ id: 'pages.table.updatedAt', defaultMessage: 'Updated At' }), value: formatTime(viewSession?.updatedAt) },
            ].map((row) => (
              <tr key={row.label}>
                <td style={{ padding: '8px 12px', fontWeight: 500, color: '#8c8c8c', borderBottom: '1px solid #f0f0f0', width: 120, verticalAlign: 'top' }}>{row.label}</td>
                <td style={{ padding: '8px 12px', borderBottom: '1px solid #f0f0f0', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{row.value || '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Modal>
      <RunDrawer
        open={runDrawerSessionId != null}
        sessionId={runDrawerSessionId}
        onClose={() => setRunDrawerSessionId(null)}
      />
      <Modal
        title={intl.formatMessage({ id: 'pages.chat.sessionSummary', defaultMessage: 'Session Summary' })}
        open={summaryOpen}
        onCancel={() => setSummaryOpen(false)}
        loading={summaryLoading}
        footer={
          <Button type="primary" onClick={handleSaveSummary} loading={summarySaving}>
            {intl.formatMessage({ id: 'pages.chat.saveSummary', defaultMessage: 'Save Summary' })}
          </Button>
        }
      >
        <Input.TextArea
          rows={4}
          value={summaryEditValue}
          onChange={(e) => setSummaryEditValue(e.target.value)}
          placeholder={intl.formatMessage({ id: 'pages.chat.noSummary', defaultMessage: 'No summary available' })}
        />
      </Modal>
    </>
  );
}

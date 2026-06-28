import { useIntl } from '@umijs/max';
import { Drawer, Empty, Input, Spin, Table, Tag } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { formatTime } from '@/utils/format';
import InteractionModal from '../InteractionModal';

interface RunDrawerProps {
  open: boolean;
  sessionId: number | null;
  onClose: () => void;
}

const PAGE_SIZE = 10;

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
  CANCELLED: 'warning',
};

export default function RunDrawer({
  open,
  sessionId,
  onClose,
}: RunDrawerProps) {
  const intl = useIntl();
  const [runs, setRuns] = useState<any[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);

  useEffect(() => {
    if (!open || sessionId == null) return;
    setKeyword('');
    setPage(1);
  }, [open, sessionId]);

  const loadRuns = useCallback(async (sid: number, p: number, kw: string) => {
    setLoading(true);
    try {
      const res = await agentApi.runs.listBySession(
        sid,
        p,
        PAGE_SIZE,
        kw || undefined,
      );
      setRuns(res.records || []);
      setTotal(res.total || 0);
      setPage(p);
    } catch {
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open && sessionId != null) {
      loadRuns(sessionId, page, keyword);
    }
  }, [open, sessionId, page, keyword, loadRuns]);

  const handleSearch = (value: string) => {
    setKeyword(value);
    setPage(1);
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: intl.formatMessage({
        id: 'pages.chat.userMessage',
        defaultMessage: 'User Message',
      }),
      dataIndex: 'userMessage',
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.chat.type',
        defaultMessage: 'Type',
      }),
      dataIndex: 'type',
      width: 80,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.status' }),
      dataIndex: 'status',
      width: 110,
      render: (s: string) => (
        <Tag color={STATUS_COLORS[s] || 'default'}>{s || '-'}</Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.created' }),
      dataIndex: 'createdAt',
      width: 160,
      render: (v: string) => formatTime(v),
    },
  ];

  return (
    <>
      <Drawer
        title={intl.formatMessage({
          id: 'pages.chat.runList',
          defaultMessage: 'Runs',
        })}
        placement="right"
        size="large"
        open={open}
        onClose={onClose}
      >
        <Input.Search
          placeholder={intl.formatMessage({
            id: 'pages.search.placeholder',
            defaultMessage: 'Search...',
          })}
          allowClear
          onSearch={handleSearch}
          style={{ marginBottom: 12 }}
        />
        <Spin spinning={loading}>
          {runs.length === 0 && !loading ? (
            <Empty
              description={intl.formatMessage({ id: 'pages.table.empty' })}
            />
          ) : (
            <Table
              dataSource={runs}
              columns={columns}
              rowKey="id"
              size="small"
              pagination={{
                current: page,
                pageSize: PAGE_SIZE,
                total,
                showSizeChanger: false,
                onChange: (p) => setPage(p),
              }}
              onRow={(record) => ({
                onClick: () => setSelectedRunId(record.id),
                style: { cursor: 'pointer' },
              })}
            />
          )}
        </Spin>
      </Drawer>
      {selectedRunId != null && (
        <InteractionModal
          open
          runId={selectedRunId}
          sessionId={sessionId}
          onClose={() => setSelectedRunId(null)}
        />
      )}
    </>
  );
}

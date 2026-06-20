import { Button, Empty, Modal, Spin, Table, Tag } from 'antd';
import { useIntl } from '@umijs/max';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { formatTime } from '@/utils/format';
import DetailModal from '../DetailModal';

interface InteractionModalProps {
  open: boolean;
  runId: number | null;
  sessionId: number | null;
  onClose: () => void;
}

const PAGE_SIZE = 10;

const TYPE_COLORS: Record<string, string> = {
  LLM_REPLY: 'blue',
  CLASSIFICATION: 'purple',
  CHAT_REPLY: 'blue',
  COMPACTION: 'orange',
  TITLE: 'cyan',
  TOOL_ARGUMENT: 'lime',
  TOOL_RESULT_SUMMARIZE: 'green',
  SKILL_EXECUTE: 'magenta',
};

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  COMPLETED: 'success',
  SUCCEEDED: 'success',
  FAILED: 'error',
  CANCELLED: 'warning',
};

export default function InteractionModal({ open, runId, sessionId, onClose }: InteractionModalProps) {
  const intl = useIntl();
  const [records, setRecords] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [detailRecord, setDetailRecord] = useState<any>(null);

  useEffect(() => {
    if (!open || runId == null || sessionId == null) return;
    setPage(1);
    setRecords([]);
    setTotal(0);
    loadActivities(runId, sessionId, 1);
  }, [open, runId, sessionId]);

  const loadActivities = async (rid: number, sid: number, p: number) => {
    setLoading(true);
    try {
      const offset = (p - 1) * PAGE_SIZE;
      const res = await agentApi.activities.listByRun(rid, sid, offset, PAGE_SIZE);
      setRecords(res.records || []);
      setTotal(res.total || 0);
      setPage(p);
    } catch {
    } finally {
      setLoading(false);
    }
  };

  const locale = intl.locale;
  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.chat.interactionType', defaultMessage: 'Type' }),
      width: 140,
      render: (_: any, r: any) =>
        r.activityType === 'llm_interaction' ? (
          <Tag color={TYPE_COLORS[r.interactionType] || 'default'}>{r.interactionType || '-'}</Tag>
        ) : (
          <Tag color="green">{locale === 'en-US' ? (r.displayNameEn || r.displayNameCn || r.toolName) : (r.displayNameCn || r.toolName)}</Tag>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.chat.model', defaultMessage: 'Model' }),
      dataIndex: 'modelName',
      width: 140,
      ellipsis: true,
      render: (v: string, r: any) => (r.activityType === 'llm_interaction' ? v || '-' : '-'),
    },
    {
      title: intl.formatMessage({ id: 'pages.chat.durationMs', defaultMessage: 'Duration' }),
      width: 80,
      render: (_: any, r: any) => (r.durationMs != null ? `${r.durationMs}ms` : '-'),
    },
    {
      title: intl.formatMessage({ id: 'pages.chat.success', defaultMessage: 'Status' }),
      width: 100,
      render: (_: any, r: any) =>
        r.activityType === 'llm_interaction' ? (
          <Tag color={r.success ? 'success' : 'error'}>{r.success ? 'OK' : 'FAIL'}</Tag>
        ) : (
          <Tag color={STATUS_COLORS[r.toolStatus] || 'default'}>{r.toolStatus || '-'}</Tag>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.created' }),
      dataIndex: 'createdAt',
      width: 160,
      render: (v: string) => formatTime(v),
    },
    {
      title: '',
      width: 60,
      render: (_: any, r: any) => (
        <Button type="link" size="small" onClick={(e) => { e.stopPropagation(); setDetailRecord(r); }}>
          {intl.formatMessage({ id: 'pages.chat.detail', defaultMessage: 'Detail' })}
        </Button>
      ),
    },
  ];

  return (
    <>
      <Modal
        title={intl.formatMessage(
          { id: 'pages.chat.interactionsForRun', defaultMessage: 'Interactions for Run #{runId}' },
          { runId },
        )}
        open={open}
        onCancel={onClose}
        width={960}
        footer={null}
      >
        <Spin spinning={loading}>
          {records.length === 0 && !loading ? (
            <Empty description={intl.formatMessage({ id: 'pages.table.empty' })} />
          ) : (
            <Table
              dataSource={records}
              columns={columns}
              rowKey={(r) => `${r.activityType}-${r.id}`}
              size="small"
              pagination={{
                current: page,
                pageSize: PAGE_SIZE,
                total,
                showSizeChanger: false,
                onChange: (p) => loadActivities(runId!, sessionId!, p),
              }}
            />
          )}
        </Spin>
      </Modal>
      <DetailModal
        open={!!detailRecord}
        record={detailRecord}
        onClose={() => setDetailRecord(null)}
      />
    </>
  );
}

import { ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Drawer, Select, Space, Table, Tag } from 'antd';
import { useRef, useState } from 'react';
import type { SessionCleanupRun } from '@/services/agentSphere/api';
import { agentApi } from '@/services/agentSphere/api';
import { formatTime } from '@/utils/format';

interface Props {
  open: boolean;
  onClose: () => void;
}

const STATUS_META: Record<string, { color: string; labelKey: string }> = {
  RUNNING: {
    color: 'processing',
    labelKey: 'pages.admin.settings.cleanup.runs.statusRunning',
  },
  SUCCESS: {
    color: 'green',
    labelKey: 'pages.admin.settings.cleanup.runs.statusSuccess',
  },
  FAILED: {
    color: 'red',
    labelKey: 'pages.admin.settings.cleanup.runs.statusFailed',
  },
  SKIPPED: {
    color: 'default',
    labelKey: 'pages.admin.settings.cleanup.runs.statusSkipped',
  },
};

/**
 * Session cleanup execution records.
 *
 * <p>Both scheduled (cron) and manual triggers appear on this timeline, and dry runs and real
 * deletions are listed together (distinguished by the "type" column) — cleanup is irreversible,
 * so "what was dry-run back then, how many rows were seen" must remain traceable.
 *
 * <p>Expanded rows show per-table row counts: the report is persisted structurally
 * (table_stats jsonb) and read directly here.
 */
export default function SessionCleanupRunsDrawer({ open, onClose }: Props) {
  const intl = useIntl();
  const actionRef = useRef<any>(null);
  const [triggerType, setTriggerType] = useState<string | undefined>();
  const [status, setStatus] = useState<string | undefined>();
  const t = (id: string, defaultMessage?: string) =>
    intl.formatMessage({ id, defaultMessage });

  const reload = () => actionRef.current?.reload();

  const statsOf = (row: SessionCleanupRun) =>
    Object.entries(row.tableStats || {})
      .filter(([, rows]) => rows > 0)
      .sort((a, b) => b[1] - a[1]);

  return (
    <Drawer
      title={t(
        'pages.admin.settings.cleanup.runs.title',
        'Session Cleanup Runs',
      )}
      size={1080}
      open={open}
      onClose={onClose}
      destroyOnHidden
    >
      <Space style={{ marginBottom: 12 }}>
        <Select
          allowClear
          style={{ width: 140 }}
          placeholder={t(
            'pages.admin.settings.cleanup.runs.filterTrigger',
            'Trigger',
          )}
          value={triggerType}
          onChange={(v) => {
            setTriggerType(v);
            reload();
          }}
          options={[
            {
              value: 'SCHEDULED',
              label: t(
                'pages.admin.settings.cleanup.runs.triggerScheduled',
                'Scheduled',
              ),
            },
            {
              value: 'MANUAL',
              label: t(
                'pages.admin.settings.cleanup.runs.triggerManual',
                'Manual',
              ),
            },
          ]}
        />
        <Select
          allowClear
          style={{ width: 140 }}
          placeholder={t(
            'pages.admin.settings.cleanup.runs.filterStatus',
            'Status',
          )}
          value={status}
          onChange={(v) => {
            setStatus(v);
            reload();
          }}
          options={[
            {
              value: 'RUNNING',
              label: t(
                'pages.admin.settings.cleanup.runs.statusRunning',
                'Running',
              ),
            },
            {
              value: 'SUCCESS',
              label: t(
                'pages.admin.settings.cleanup.runs.statusSuccess',
                'Success',
              ),
            },
            {
              value: 'FAILED',
              label: t(
                'pages.admin.settings.cleanup.runs.statusFailed',
                'Failed',
              ),
            },
            {
              value: 'SKIPPED',
              label: t(
                'pages.admin.settings.cleanup.runs.statusSkipped',
                'Skipped',
              ),
            },
          ]}
        />
      </Space>
      <ProTable
        actionRef={actionRef}
        rowKey="id"
        size="small"
        search={false}
        options={{ reload: false, density: false, setting: false }}
        columns={[
          {
            title: t('pages.table.id'),
            dataIndex: 'id',
            width: 70,
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.trigger',
              'Trigger',
            ),
            dataIndex: 'triggerType',
            width: 90,
            render: (_, row: SessionCleanupRun) =>
              row.triggerType === 'MANUAL' ? (
                <Tag color="blue">
                  {t(
                    'pages.admin.settings.cleanup.runs.triggerManual',
                    'Manual',
                  )}
                </Tag>
              ) : (
                <Tag>
                  {t(
                    'pages.admin.settings.cleanup.runs.triggerScheduled',
                    'Scheduled',
                  )}
                </Tag>
              ),
          },
          {
            title: t('pages.admin.settings.cleanup.runs.col.kind', 'Kind'),
            dataIndex: 'dryRun',
            width: 90,
            render: (_, row: SessionCleanupRun) =>
              row.dryRun ? (
                <Tag color="gold">
                  {t('pages.admin.settings.cleanup.runs.dryRun', 'Preview')}
                </Tag>
              ) : (
                <Tag color="red">
                  {t('pages.admin.settings.cleanup.runs.realRun', 'Deleted')}
                </Tag>
              ),
          },
          {
            title: t('pages.admin.settings.cleanup.runs.col.status', 'Status'),
            dataIndex: 'status',
            width: 120,
            render: (_, row: SessionCleanupRun) => {
              // A crashed process leaves the record stuck in RUNNING forever: flag it so it
              // is not misread as "cleaning up right now"
              if (row.stale) {
                return (
                  <Tag color="red">
                    {t(
                      'pages.admin.settings.cleanup.runs.stale',
                      'Likely crashed',
                    )}
                  </Tag>
                );
              }
              const meta = STATUS_META[row.status];
              return (
                <Tag color={meta?.color}>
                  {meta ? t(meta.labelKey) : row.status}
                </Tag>
              );
            },
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.retention',
              'Retention',
            ),
            dataIndex: 'retentionDays',
            width: 100,
            render: (_, row: SessionCleanupRun) =>
              row.retentionDays
                ? `${row.retentionDays}/${row.fileRetentionDays}`
                : '-',
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.sessions',
              'Sessions',
            ),
            dataIndex: 'sessionCount',
            width: 90,
            align: 'right' as const,
          },
          {
            title: t('pages.admin.settings.cleanup.runs.col.rows', 'Rows'),
            dataIndex: 'totalRows',
            width: 110,
            align: 'right' as const,
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.activeBlocked',
              'Active skipped',
            ),
            dataIndex: 'skippedActiveSessions',
            width: 100,
            align: 'right' as const,
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.elapsed',
              'Elapsed',
            ),
            dataIndex: 'elapsedMs',
            width: 100,
            align: 'right' as const,
            render: (_, row: SessionCleanupRun) =>
              row.elapsedMs == null ? '-' : `${row.elapsedMs} ms`,
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.startedAt',
              'Started at',
            ),
            dataIndex: 'startedAt',
            width: 170,
            render: (_, row: SessionCleanupRun) => formatTime(row.startedAt),
          },
        ]}
        expandable={{
          expandedRowRender: (row: SessionCleanupRun) => (
            <Space direction="vertical" style={{ width: '100%' }}>
              {row.skipReason && (
                <span>
                  {t(
                    'pages.admin.settings.cleanup.runs.skipReason',
                    'Skip reason',
                  )}
                  ：{row.skipReason}
                </span>
              )}
              {row.errorMessage && (
                <span>
                  {t('pages.admin.settings.cleanup.runs.errorMessage', 'Error')}
                  ：{row.errorMessage}
                </span>
              )}
              {row.truncated && (
                <span>
                  {t(
                    'pages.admin.settings.cleanup.runs.truncated',
                    'Batch limit reached, data still pending',
                  )}
                </span>
              )}
              <Table
                size="small"
                pagination={false}
                rowKey="table"
                dataSource={statsOf(row).map(([table, rows]) => ({
                  table,
                  rows,
                }))}
                columns={[
                  {
                    title: t('pages.admin.settings.cleanup.col.table', 'Table'),
                    dataIndex: 'table',
                  },
                  {
                    title: t('pages.admin.settings.cleanup.col.rows', 'Rows'),
                    dataIndex: 'rows',
                    width: 120,
                    align: 'right',
                  },
                ]}
              />
            </Space>
          ),
        }}
        request={async (p) => {
          const res = await agentApi.admin.listSessionCleanupRuns({
            triggerType,
            status,
            page: p.current,
            size: p.pageSize,
          });
          return {
            data: res?.records || [],
            total: res?.total ?? 0,
            success: true,
          };
        }}
      />
    </Drawer>
  );
}

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

const STATUS_META: Record<string, { color: string; label: string }> = {
  RUNNING: { color: 'processing', label: '运行中' },
  SUCCESS: { color: 'green', label: '成功' },
  FAILED: { color: 'red', label: '失败' },
  SKIPPED: { color: 'default', label: '已跳过' },
};

/**
 * 会话清理的执行记录。
 *
 * <p>定时（cron）与手动两种触发都在这条时间线上，预演与真实删除也一起列出
 * （靠「类型」列区分）—— 清理不可逆，「当时预演过什么、看到多少行」需要能回溯。
 *
 * <p>展开行显示各表行数明细：报告结构化落库（table_stats jsonb），这里直接读。
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
      title={t('pages.admin.settings.cleanup.runs.title', '会话清理执行记录')}
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
            '触发方式',
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
                '定时',
              ),
            },
            {
              value: 'MANUAL',
              label: t(
                'pages.admin.settings.cleanup.runs.triggerManual',
                '手动',
              ),
            },
          ]}
        />
        <Select
          allowClear
          style={{ width: 140 }}
          placeholder={t(
            'pages.admin.settings.cleanup.runs.filterStatus',
            '状态',
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
                '运行中',
              ),
            },
            {
              value: 'SUCCESS',
              label: t(
                'pages.admin.settings.cleanup.runs.statusSuccess',
                '成功',
              ),
            },
            {
              value: 'FAILED',
              label: t(
                'pages.admin.settings.cleanup.runs.statusFailed',
                '失败',
              ),
            },
            {
              value: 'SKIPPED',
              label: t(
                'pages.admin.settings.cleanup.runs.statusSkipped',
                '已跳过',
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
              '触发方式',
            ),
            dataIndex: 'triggerType',
            width: 90,
            render: (_, row: SessionCleanupRun) =>
              row.triggerType === 'MANUAL' ? (
                <Tag color="blue">
                  {t('pages.admin.settings.cleanup.runs.triggerManual', '手动')}
                </Tag>
              ) : (
                <Tag>
                  {t(
                    'pages.admin.settings.cleanup.runs.triggerScheduled',
                    '定时',
                  )}
                </Tag>
              ),
          },
          {
            title: t('pages.admin.settings.cleanup.runs.col.kind', '类型'),
            dataIndex: 'dryRun',
            width: 90,
            render: (_, row: SessionCleanupRun) =>
              row.dryRun ? (
                <Tag color="gold">
                  {t('pages.admin.settings.cleanup.runs.dryRun', '预演')}
                </Tag>
              ) : (
                <Tag color="red">
                  {t('pages.admin.settings.cleanup.runs.realRun', '已删除')}
                </Tag>
              ),
          },
          {
            title: t('pages.admin.settings.cleanup.runs.col.status', '状态'),
            dataIndex: 'status',
            width: 120,
            render: (_, row: SessionCleanupRun) => {
              // 进程崩了会让记录永远停在 RUNNING：标出来，别被误读成「正在清理」
              if (row.stale) {
                return (
                  <Tag color="red">
                    {t('pages.admin.settings.cleanup.runs.stale', '疑似中断')}
                  </Tag>
                );
              }
              const meta = STATUS_META[row.status];
              return <Tag color={meta?.color}>{meta?.label || row.status}</Tag>;
            },
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.retention',
              '保留天数',
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
              '会话数',
            ),
            dataIndex: 'sessionCount',
            width: 90,
            align: 'right' as const,
          },
          {
            title: t('pages.admin.settings.cleanup.runs.col.rows', '删除行数'),
            dataIndex: 'totalRows',
            width: 110,
            align: 'right' as const,
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.activeBlocked',
              '活跃跳过',
            ),
            dataIndex: 'skippedActiveSessions',
            width: 100,
            align: 'right' as const,
          },
          {
            title: t('pages.admin.settings.cleanup.runs.col.elapsed', '耗时'),
            dataIndex: 'elapsedMs',
            width: 100,
            align: 'right' as const,
            render: (_, row: SessionCleanupRun) =>
              row.elapsedMs == null ? '-' : `${row.elapsedMs} ms`,
          },
          {
            title: t(
              'pages.admin.settings.cleanup.runs.col.startedAt',
              '开始时间',
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
                    '跳过原因',
                  )}
                  ：{row.skipReason}
                </span>
              )}
              {row.errorMessage && (
                <span>
                  {t('pages.admin.settings.cleanup.runs.errorMessage', '错误')}
                  ：{row.errorMessage}
                </span>
              )}
              {row.truncated && (
                <span>
                  {t(
                    'pages.admin.settings.cleanup.runs.truncated',
                    '本轮已达批次上限，仍有数据未清理',
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
                    title: t(
                      'pages.admin.settings.cleanup.col.table',
                      '数据表',
                    ),
                    dataIndex: 'table',
                  },
                  {
                    title: t('pages.admin.settings.cleanup.col.rows', '行数'),
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

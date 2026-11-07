import { SKILL_SYNC_RUN_STATUS } from '@/constants/status';
import { ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Drawer, Select, Space, Table, Tag } from 'antd';
import { useRef, useState } from 'react';
import type { SkillSyncRun } from '@/services/agentSphere/api';
import { agentApi } from '@/services/agentSphere/api';
import { formatTime } from '@/utils/format';

interface Props {
  open: boolean;
  onClose: () => void;
}

const STATUS_META = SKILL_SYNC_RUN_STATUS;

/**
 * Skill Hub auto-sync execution records.
 *
 * <p>This list is the only evidence of whether auto-sync actually ran: the task's logger is
 * pinned to WARN, so logs alone can never answer "did it run, how many were scanned, how many
 * were synced".
 * The entry point is on the "System Settings" page, next to the session cleanup records — both
 * are system-level scheduled tasks.
 */
export default function SkillSyncRunsDrawer({ open, onClose }: Props) {
  const intl = useIntl();
  const actionRef = useRef<any>(null);
  const [triggerType, setTriggerType] = useState<string | undefined>();
  const [status, setStatus] = useState<string | undefined>();
  const t = (id: string, defaultMessage?: string) =>
    intl.formatMessage({ id, defaultMessage });

  const reload = () => actionRef.current?.reload();

  return (
    <Drawer
      title={t('pages.admin.settings.skillSyncRuns.title', 'Skill Sync Runs')}
      size={1000}
      open={open}
      onClose={onClose}
      destroyOnHidden
    >
      <Space style={{ marginBottom: 12 }}>
        <Select
          allowClear
          style={{ width: 130 }}
          placeholder={t(
            'pages.admin.settings.skillSyncRuns.filterTrigger',
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
                'pages.admin.settings.skillSyncRuns.scheduled',
                'Scheduled',
              ),
            },
            {
              value: 'MANUAL',
              label: t('pages.admin.settings.skillSyncRuns.manual', 'Manual'),
            },
          ]}
        />
        <Select
          allowClear
          style={{ width: 130 }}
          placeholder={t(
            'pages.admin.settings.skillSyncRuns.filterStatus',
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
              label: t('pages.admin.settings.skillSyncRuns.running', 'Running'),
            },
            {
              value: 'SUCCESS',
              label: t('pages.admin.settings.skillSyncRuns.success', 'Done'),
            },
            {
              value: 'FAILED',
              label: t('pages.admin.settings.skillSyncRuns.failed', 'Failed'),
            },
            {
              value: 'SKIPPED',
              label: t('pages.admin.settings.skillSyncRuns.skipped', 'Skipped'),
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
          { title: t('pages.table.id'), dataIndex: 'id', width: 70 },
          {
            title: t(
              'pages.admin.settings.skillSyncRuns.col.trigger',
              'Trigger',
            ),
            dataIndex: 'triggerType',
            width: 90,
            render: (_: any, row: SkillSyncRun) =>
              row.triggerType === 'MANUAL' ? (
                <Tag color="blue">
                  {t('pages.admin.settings.skillSyncRuns.manual', 'Manual')}
                </Tag>
              ) : (
                <Tag>
                  {t(
                    'pages.admin.settings.skillSyncRuns.scheduled',
                    'Scheduled',
                  )}
                </Tag>
              ),
          },
          {
            title: t('pages.admin.settings.skillSyncRuns.col.status', 'Status'),
            dataIndex: 'status',
            width: 120,
            render: (_: any, row: SkillSyncRun) => {
              if (row.stale) {
                return (
                  <Tag color="red">
                    {t(
                      'pages.admin.settings.skillSyncRuns.stale',
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
              'pages.admin.settings.skillSyncRuns.col.scanned',
              'Scanned',
            ),
            dataIndex: 'scannedCount',
            width: 80,
            align: 'right' as const,
          },
          {
            title: t(
              'pages.admin.settings.skillSyncRuns.col.updated',
              'Updated',
            ),
            dataIndex: 'updated',
            width: 80,
            align: 'right' as const,
            render: (_: any, row: SkillSyncRun) => (
              <Tag color={(row.updated ?? 0) > 0 ? 'green' : 'default'}>
                {row.updated ?? 0}
              </Tag>
            ),
          },
          {
            title: t(
              'pages.admin.settings.skillSyncRuns.col.skipped',
              'Not updated',
            ),
            dataIndex: 'skipped',
            width: 90,
            align: 'right' as const,
          },
          {
            title: t(
              'pages.admin.settings.skillSyncRuns.col.elapsed',
              'Elapsed',
            ),
            dataIndex: 'elapsedMs',
            width: 90,
            align: 'right' as const,
            render: (_: any, row: SkillSyncRun) =>
              row.elapsedMs == null ? '-' : `${row.elapsedMs} ms`,
          },
          {
            title: t(
              'pages.admin.settings.skillSyncRuns.col.startedAt',
              'Started at',
            ),
            dataIndex: 'startedAt',
            width: 170,
            render: (_: any, row: SkillSyncRun) => formatTime(row.startedAt),
          },
        ]}
        expandable={{
          expandedRowRender: (row: SkillSyncRun) => (
            <Space direction="vertical" style={{ width: '100%' }}>
              {row.skipReason && (
                <span>
                  {t(
                    'pages.admin.settings.skillSyncRuns.skipReason',
                    'Skip reason',
                  )}
                  ：{row.skipReason}
                </span>
              )}
              {row.errorMessage && (
                <span>
                  {t(
                    'pages.admin.settings.skillSyncRuns.errorMessage',
                    'Error',
                  )}
                  ：{row.errorMessage}
                </span>
              )}
              {(row.detail || []).length > 0 && (
                <Table
                  size="small"
                  pagination={false}
                  rowKey={(r: { copyId: number; reason: string }) =>
                    `${r.copyId}-${r.reason}`
                  }
                  dataSource={row.detail}
                  columns={[
                    {
                      title: t(
                        'pages.admin.settings.skillSync.copyId',
                        'Copy ID',
                      ),
                      dataIndex: 'copyId',
                      width: 90,
                    },
                    {
                      title: t(
                        'pages.admin.settings.skillSync.originId',
                        'Origin ID',
                      ),
                      dataIndex: 'originId',
                      width: 90,
                    },
                    {
                      title: t(
                        'pages.admin.settings.skillSync.reason',
                        'Reason',
                      ),
                      dataIndex: 'reason',
                      render: (v: string) => <Tag>{v}</Tag>,
                    },
                  ]}
                />
              )}
            </Space>
          ),
        }}
        request={async (p) => {
          const res = await agentApi.admin.listSkillSyncRuns({
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

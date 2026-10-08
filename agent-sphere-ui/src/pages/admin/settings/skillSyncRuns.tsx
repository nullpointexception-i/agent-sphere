import { ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Drawer, Select, Space, Table, Tag } from 'antd';
import { useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import type { SkillSyncRun } from '@/services/agentSphere/api';
import { formatTime } from '@/utils/format';

interface Props {
  open: boolean;
  onClose: () => void;
}

const STATUS_META: Record<string, { color: string; label: string }> = {
  RUNNING: { color: 'processing', label: '运行中' },
  SUCCESS: { color: 'green', label: '完成' },
  FAILED: { color: 'red', label: '失败' },
  SKIPPED: { color: 'default', label: '已跳过' },
};

/**
 * Skill Hub 自动同步的执行记录。
 *
 * <p>这张列表是「自动同步到底跑没跑」的唯一证据：任务的 logger 被压到 WARN，
 * 光看日志永远回答不了「跑了没有、扫到几条、同步了几条」。
 * 入口在「系统设置」页，与会话清理的执行记录并列 —— 两者都是系统级定时任务。
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
      title={t('pages.admin.settings.skillSyncRuns.title', 'Skill 同步执行记录')}
      width={1000}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      <Space style={{ marginBottom: 12 }}>
        <Select
          allowClear
          style={{ width: 130 }}
          placeholder={t('pages.admin.settings.skillSyncRuns.filterTrigger', '触发方式')}
          value={triggerType}
          onChange={(v) => {
            setTriggerType(v);
            reload();
          }}
          options={[
            {
              value: 'SCHEDULED',
              label: t('pages.admin.settings.skillSyncRuns.scheduled', '定时'),
            },
            {
              value: 'MANUAL',
              label: t('pages.admin.settings.skillSyncRuns.manual', '手动'),
            },
          ]}
        />
        <Select
          allowClear
          style={{ width: 130 }}
          placeholder={t('pages.admin.settings.skillSyncRuns.filterStatus', '状态')}
          value={status}
          onChange={(v) => {
            setStatus(v);
            reload();
          }}
          options={[
            { value: 'RUNNING', label: t('pages.admin.settings.skillSyncRuns.running', '运行中') },
            { value: 'SUCCESS', label: t('pages.admin.settings.skillSyncRuns.success', '完成') },
            { value: 'FAILED', label: t('pages.admin.settings.skillSyncRuns.failed', '失败') },
            { value: 'SKIPPED', label: t('pages.admin.settings.skillSyncRuns.skipped', '已跳过') },
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
            title: t('pages.admin.settings.skillSyncRuns.col.trigger', '触发方式'),
            dataIndex: 'triggerType',
            width: 90,
            render: (_: any, row: SkillSyncRun) =>
              row.triggerType === 'MANUAL' ? (
                <Tag color="blue">
                  {t('pages.admin.settings.skillSyncRuns.manual', '手动')}
                </Tag>
              ) : (
                <Tag>{t('pages.admin.settings.skillSyncRuns.scheduled', '定时')}</Tag>
              ),
          },
          {
            title: t('pages.admin.settings.skillSyncRuns.col.status', '状态'),
            dataIndex: 'status',
            width: 120,
            render: (_: any, row: SkillSyncRun) => {
              if (row.stale) {
                return (
                  <Tag color="red">
                    {t('pages.admin.settings.skillSyncRuns.stale', '疑似中断')}
                  </Tag>
                );
              }
              const meta = STATUS_META[row.status];
              return <Tag color={meta?.color}>{meta?.label || row.status}</Tag>;
            },
          },
          {
            title: t('pages.admin.settings.skillSyncRuns.col.scanned', '扫描'),
            dataIndex: 'scannedCount',
            width: 80,
            align: 'right' as const,
          },
          {
            title: t('pages.admin.settings.skillSyncRuns.col.updated', '更新'),
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
            title: t('pages.admin.settings.skillSyncRuns.col.skipped', '未更新'),
            dataIndex: 'skipped',
            width: 90,
            align: 'right' as const,
          },
          {
            title: t('pages.admin.settings.skillSyncRuns.col.elapsed', '耗时'),
            dataIndex: 'elapsedMs',
            width: 90,
            align: 'right' as const,
            render: (_: any, row: SkillSyncRun) =>
              row.elapsedMs == null ? '-' : `${row.elapsedMs} ms`,
          },
          {
            title: t('pages.admin.settings.skillSyncRuns.col.startedAt', '开始时间'),
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
                  {t('pages.admin.settings.skillSyncRuns.skipReason', '跳过原因')}：{row.skipReason}
                </span>
              )}
              {row.errorMessage && (
                <span>
                  {t('pages.admin.settings.skillSyncRuns.errorMessage', '错误')}：{row.errorMessage}
                </span>
              )}
              {(row.detail || []).length > 0 && (
                <Table
                  size="small"
                  pagination={false}
                  rowKey={(r: { copyId: number; reason: string }) => `${r.copyId}-${r.reason}`}
                  dataSource={row.detail}
                  columns={[
                    {
                      title: t('pages.admin.settings.skillSync.copyId', '副本 ID'),
                      dataIndex: 'copyId',
                      width: 90,
                    },
                    {
                      title: t('pages.admin.settings.skillSync.originId', '源技能 ID'),
                      dataIndex: 'originId',
                      width: 90,
                    },
                    {
                      title: t('pages.admin.settings.skillSync.reason', '未更新原因'),
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
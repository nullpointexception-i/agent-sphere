import { useIntl } from '@umijs/max';
import { Alert, Descriptions, Modal, Progress, Table, Tag } from 'antd';
import type { SkillSyncRun } from '@/services/agentSphere/api';

interface Props {
  open: boolean;
  /** 执行记录（含实时统计与跳过明细）；null 表示还没提交成功 */
  run: SkillSyncRun | null;
  onClose: () => void;
}

/**
 * Skill Hub 自动同步的执行面板。
 *
 * <p>与 {@link ./sessionCleanup 会话清理}面板同一形状（共用 `useRecordedTask` 轮询），
 * 同样挂在「系统设置」页而不是技能列表 —— 它是系统级定时任务的手动触发入口，
 * 由系统配置（`skill.auto-update-enabled` 等）驱动。
 * RUNNING 显示进度，终态显示统计与跳过明细；没有二次确认按钮 —— 同步是幂等覆盖。
 */
export default function SkillSyncRunPanel({ open, run, onClose }: Props) {
  const intl = useIntl();
  const t = (id: string, defaultMessage?: string, values?: Record<string, any>) =>
    intl.formatMessage({ id, defaultMessage }, values);

  const running = run?.status === 'RUNNING';
  const scanned = run?.scannedCount ?? 0;
  const processed = run?.handled ?? 0;
  // 分母是「本轮扫到的副本数」，它在一轮内会随进度更新；还没拿到时留空避免除零
  const percent =
    scanned > 0 ? Math.min(100, Math.round((processed / scanned) * 100)) : undefined;

  const details = (run?.detail || []).filter(Boolean);

  return (
    <Modal
      open={open}
      title={t('pages.admin.settings.skillSync.title', '同步 Skill Hub 最新版本')}
      onCancel={onClose}
      maskClosable={false}
      footer={null}
      width={720}
    >
      {run ? (
        <>
          {running && (
            <div style={{ marginBottom: 16 }}>
              <Progress
                percent={percent}
                status="active"
                format={() =>
                  scanned > 0
                    ? `${percent ?? 0}%`
                    : t('pages.admin.settings.skillSync.scanning', '扫描中…')
                }
              />
              <div style={{ marginTop: 8, color: 'rgba(0,0,0,0.45)' }}>
                {t(
                  'pages.admin.settings.skillSync.hint',
                  '已处理 {done} / {total} 个副本 · 更新 {updated} 个 · 跳过 {skipped} 个',
                  {
                    done: processed,
                    total: scanned,
                    updated: run?.updated ?? 0,
                    skipped: run?.skipped ?? 0,
                  },
                )}
              </div>
            </div>
          )}

          {run.status === 'SKIPPED' && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={
                run.skipReason ||
                t('pages.admin.settings.skillSync.skipped', '本次未执行')
              }
            />
          )}
          {run.status === 'FAILED' && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message={
                run.errorMessage ||
                t('pages.admin.settings.skillSync.failed', '执行失败，请查看后端日志')
              }
            />
          )}
          {run.stale && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={t(
                'pages.admin.settings.skillSync.stale',
                '该记录长时间停在运行中，进程可能已中断，请查看后端日志确认。',
              )}
            />
          )}

          {!running && (
            <Descriptions size="small" column={2} bordered style={{ marginBottom: 16 }}>
              <Descriptions.Item
                label={t('pages.admin.settings.skillSync.scanned', '扫描副本数')}
              >
                {run.scannedCount ?? 0}
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.skillSync.updated', '已更新')}
              >
                <Tag color={(run.updated ?? 0) > 0 ? 'green' : 'default'}>
                  {run.updated ?? 0}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.skillSync.skippedCount', '未更新')}
              >
                {run.skipped ?? 0}
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.skillSync.elapsed', '耗时')}
              >
                {run.elapsedMs == null ? '-' : `${run.elapsedMs} ms`}
              </Descriptions.Item>
            </Descriptions>
          )}

          {!running && details.length > 0 && (
            <Table
              size="small"
              pagination={false}
              scroll={{ y: 240 }}
              rowKey={(row: { copyId: number; reason: string }) => `${row.copyId}-${row.reason}`}
              dataSource={details}
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
                  render: (v: string) => <Tag color={reasonColor(v)}>{v}</Tag>,
                },
              ]}
            />
          )}
          {!running && run.status === 'SUCCESS' && details.length === 0 && (
            <Alert
              type="success"
              showIcon
              message={t(
                'pages.admin.settings.skillSync.allSynced',
                '全部副本都已是源头最新版本。',
              )}
            />
          )}
        </>
      ) : (
        <Alert
          type="info"
          showIcon
          message={t('pages.admin.settings.skillSync.submitting', '正在提交同步任务…')}
        />
      )}
    </Modal>
  );
}

/** 不同原因给不同颜色：源没了/取消公开是需要人处理的，版本未领先是正常态。 */
function reasonColor(reason: string) {
  if (reason.startsWith('ERROR')) return 'red';
  if (reason === 'SOURCE_GONE' || reason === 'NOT_PUBLIC') return 'orange';
  return 'default';
}
import { useIntl } from '@umijs/max';
import { Alert, Button, Descriptions, Modal, Progress, Table, Tag } from 'antd';
import type { SessionCleanupRun } from '@/services/agentSphere/api';

interface Props {
  open: boolean;
  /** 执行记录（含实时进度）；null 表示还没提交成功 */
  run: SessionCleanupRun | null;
  submitting: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

const RUNNING = 'RUNNING';

/**
 * 会话清理的执行面板。
 *
 * <p>一个面板承担两种模式，因为它们的数据源是同一个东西 —— 执行记录：
 * <ul>
 *   <li>RUNNING：进度条 + 已处理会话数 / 已清理行数 / 耗时（后端每批写一次，这里 1s 轮询）；</li>
 *   <li>预演终态：各表行数明细 + 底部「确认删除 N 个会话」；</li>
 *   <li>执行终态：各表行数明细 + 后端给的 VACUUM 建议；</li>
 *   <li>SKIPPED / FAILED：分别展示跳过原因与错误。</li>
 * </ul>
 */
export default function SessionCleanupRunPanel({
  open,
  run,
  submitting,
  onClose,
  onConfirm,
}: Props) {
  const intl = useIntl();
  const t = (
    id: string,
    defaultMessage?: string,
    values?: Record<string, any>,
  ) => intl.formatMessage({ id, defaultMessage }, values);

  const running = run?.status === RUNNING;
  const dryRun = !!run?.dryRun;
  const processed = run?.sessionCount ?? 0;
  const total = run?.totalSessions ?? 0;
  const percent =
    total > 0
      ? Math.min(100, Math.round((processed / total) * 100))
      : undefined;

  const stats = Object.entries(run?.tableStats || {})
    .filter(([, rows]) => rows > 0)
    .sort((a, b) => b[1] - a[1]);

  return (
    <Modal
      open={open}
      title={t('pages.admin.settings.cleanup.modal.title', '会话清理')}
      onCancel={onClose}
      maskClosable={false}
      footer={[
        <Button key="close" onClick={onClose}>
          {running
            ? t(
                'pages.admin.settings.cleanup.closeInBackground',
                '后台继续执行',
              )
            : t('pages.cancel')}
        </Button>,
        // 只有「预演已出结果、且确实有东西可删」才允许真删
        dryRun && !running ? (
          <Button
            key="confirm"
            danger
            type="primary"
            disabled={stats.length === 0}
            loading={submitting}
            onClick={onConfirm}
          >
            {t(
              'pages.admin.settings.cleanup.confirm',
              '确认删除 {count} 个会话',
              { count: run?.sessionCount ?? 0 },
            )}
          </Button>
        ) : null,
      ]}
    >
      {run ? (
        <>
          {running && (
            <div style={{ marginBottom: 16 }}>
              <Progress
                percent={percent}
                status="active"
                // 分母为 0 说明没有过期数据，进度条只会永远空转，直接说明原因
                format={() =>
                  total > 0
                    ? `${percent ?? 0}%`
                    : t('pages.admin.settings.cleanup.noData', '统计中…')
                }
              />
              <div style={{ marginTop: 8, color: 'rgba(0,0,0,0.45)' }}>
                {t(
                  'pages.admin.settings.cleanup.progress.hint',
                  '已处理 {done} / {total} 个会话 · 已清理 {rows} 行 · 已用 {elapsed} ms',
                  {
                    done: processed,
                    total,
                    rows: run.totalRows ?? 0,
                    elapsed: run.elapsedMs ?? 0,
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
                t('pages.admin.settings.cleanup.skipped', '本次未执行')
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
                t(
                  'pages.admin.settings.cleanup.failed',
                  '执行失败，请查看后端日志',
                )
              }
            />
          )}
          {run.stale && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={t(
                'pages.admin.settings.cleanup.staleHint',
                '该记录长时间停在运行中，进程可能已中断，请查看后端日志确认。',
              )}
            />
          )}

          {!running && dryRun && run.status === 'SUCCESS' && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={t(
                'pages.admin.settings.cleanup.alert.dryRun',
                '这是预演结果，尚未删除任何数据。',
              )}
            />
          )}
          {!running && !dryRun && run.status === 'SUCCESS' && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message={t(
                'pages.admin.settings.cleanup.alert.irreversible',
                '清理为不可逆硬删：关联会话、运行记录、交互正文、截图附件均已永久删除，无法恢复。',
              )}
            />
          )}
          {!running && (run.skippedActiveSessions ?? 0) > 0 && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message={t(
                'pages.admin.settings.cleanup.alert.activeBlocked',
                '另有 {count} 个会话因仍有进行中的运行或任务被保护，本次未清理。若这个数量长期偏大，说明保留天数相对业务节奏太激进。',
                { count: run.skippedActiveSessions },
              )}
            />
          )}
          {!running && run.truncated && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={t(
                'pages.admin.settings.cleanup.alert.truncated',
                '本轮已达批次上限，仍有数据未清理，可稍后再次执行。',
              )}
            />
          )}

          {!running && (
            <Descriptions
              size="small"
              column={2}
              bordered
              style={{ marginBottom: 16 }}
            >
              <Descriptions.Item
                label={t('pages.admin.settings.cleanup.sessions', '会话数')}
              >
                <Tag color={(run.sessionCount ?? 0) > 0 ? 'red' : 'default'}>
                  {run.sessionCount ?? 0}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.cleanup.rows', '删除行数')}
              >
                {run.totalRows ?? 0}
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.cleanup.cutoff', '过期判定线')}
              >
                {run.cutoff || '-'}
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.cleanup.retention', '保留天数')}
              >
                {run.retentionDays ?? '-'} / {run.fileRetentionDays ?? '-'}
              </Descriptions.Item>
            </Descriptions>
          )}

          {!running && stats.length > 0 && (
            <Table
              size="small"
              dataSource={stats.map(([table, rows]) => ({ table, rows }))}
              rowKey="table"
              pagination={false}
              scroll={{ y: 240 }}
              columns={[
                {
                  title: t('pages.admin.settings.cleanup.col.table', '数据表'),
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
          )}

          {!running && run.remark && (
            <Alert
              type="info"
              showIcon
              style={{ marginTop: 16 }}
              message={t(
                'pages.admin.settings.cleanup.vacuumHint',
                '磁盘处置建议',
              )}
              description={run.remark}
            />
          )}
        </>
      ) : (
        <Alert
          type="info"
          showIcon
          message={t(
            'pages.admin.settings.cleanup.submitting',
            '正在提交清理任务…',
          )}
        />
      )}
    </Modal>
  );
}

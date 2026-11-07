import { useIntl } from '@umijs/max';
import { Alert, Button, Descriptions, Modal, Progress, Table, Tag } from 'antd';
import { STATUS } from '@/constants/status';
import type { SessionCleanupRun } from '@/services/agentSphere/api';

interface Props {
  open: boolean;
  /** Execution record (with live progress); null means no successful submission yet */
  run: SessionCleanupRun | null;
  submitting: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

const RUNNING = STATUS.CLEANUP.RUNNING;

/**
 * Session cleanup run panel.
 *
 * <p>One panel serves two modes because they share the same data source — the execution record:
 * <ul>
 *   <li>RUNNING: progress bar + processed sessions / deleted rows / elapsed time (the backend writes once per batch; polled every 1s here);</li>
 *   <li>Dry-run terminal state: row counts per table + a bottom "confirm deletion of N sessions";</li>
 *   <li>Real-run terminal state: row counts per table + the backend's VACUUM recommendation;</li>
 *   <li>SKIPPED / FAILED: show the skip reason and error respectively.</li>
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
      title={t(
        'pages.admin.settings.cleanup.modal.title',
        'Session Cleanup Report',
      )}
      onCancel={onClose}
      maskClosable={false}
      footer={[
        <Button key="close" onClick={onClose}>
          {running
            ? t(
                'pages.admin.settings.cleanup.closeInBackground',
                'Runs in background',
              )
            : t('pages.cancel')}
        </Button>,
        // Only allow a real delete when the dry run has results and there is actually something to delete
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
              'Delete {count} sessions',
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
                // A zero denominator means there is no expired data, so the bar would spin
                // forever; state the reason directly instead
                format={() =>
                  total > 0
                    ? `${percent ?? 0}%`
                    : t('pages.admin.settings.cleanup.noData', 'Counting…')
                }
              />
              <div style={{ marginTop: 8, color: 'rgba(0,0,0,0.45)' }}>
                {t(
                  'pages.admin.settings.cleanup.progress.hint',
                  'Processed {done} / {total} sessions · {rows} rows deleted · {elapsed} ms',
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

          {run.status === STATUS.CLEANUP.SKIPPED && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={
                run.skipReason ||
                t(
                  'pages.admin.settings.cleanup.skipped',
                  'Nothing was executed',
                )
              }
            />
          )}
          {run.status === STATUS.CLEANUP.FAILED && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message={
                run.errorMessage ||
                t(
                  'pages.admin.settings.cleanup.failed',
                  'Cleanup failed, check backend logs',
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
                'This record has been running for too long; the process may have died. Check backend logs.',
              )}
            />
          )}

          {!running && dryRun && run.status === STATUS.CLEANUP.SUCCESS && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={t(
                'pages.admin.settings.cleanup.alert.dryRun',
                'Preview only — nothing has been deleted.',
              )}
            />
          )}
          {!running && !dryRun && run.status === STATUS.CLEANUP.SUCCESS && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message={t(
                'pages.admin.settings.cleanup.alert.irreversible',
                'Cleanup is an irreversible hard delete: sessions, runs, interaction payloads and screenshots are permanently removed.',
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
                '{count} sessions were skipped because runs or tasks are still active. A persistently large number means the retention window is too aggressive.',
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
                'Batch limit reached; some data is still pending. Run again later.',
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
                label={t('pages.admin.settings.cleanup.sessions', 'Sessions')}
              >
                <Tag color={(run.sessionCount ?? 0) > 0 ? 'red' : 'default'}>
                  {run.sessionCount ?? 0}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.cleanup.rows', 'Rows deleted')}
              >
                {run.totalRows ?? 0}
              </Descriptions.Item>
              <Descriptions.Item
                label={t(
                  'pages.admin.settings.cleanup.cutoff',
                  'Expiry cutoff',
                )}
              >
                {run.cutoff || '-'}
              </Descriptions.Item>
              <Descriptions.Item
                label={t(
                  'pages.admin.settings.cleanup.retention',
                  'Retention (days)',
                )}
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
          )}

          {!running && run.remark && (
            <Alert
              type="info"
              showIcon
              style={{ marginTop: 16 }}
              message={t(
                'pages.admin.settings.cleanup.vacuumHint',
                'Disk reclaim hint',
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
            'Submitting cleanup task…',
          )}
        />
      )}
    </Modal>
  );
}

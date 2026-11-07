import { useIntl } from '@umijs/max';
import { Alert, Descriptions, Modal, Progress, Table, Tag } from 'antd';
import type { SkillSyncRun } from '@/services/agentSphere/api';

interface Props {
  open: boolean;
  /** Execution record (with live stats and skipped details); null means no successful submission yet */
  run: SkillSyncRun | null;
  onClose: () => void;
}

/**
 * Skill Hub auto-sync run panel.
 *
 * <p>Same shape as the {@link ./sessionCleanup session cleanup} panel (they share `useRecordedTask`
 * polling), and it also lives on the "System Settings" page rather than the skill list — it is the
 * manual trigger for a system-level scheduled task, driven by system configuration
 * (`skill.auto-update-enabled`, etc.).
 * RUNNING shows progress; terminal states show stats and skipped details. There is no confirmation
 * button — syncing is an idempotent overwrite.
 */
export default function SkillSyncRunPanel({ open, run, onClose }: Props) {
  const intl = useIntl();
  const t = (
    id: string,
    defaultMessage?: string,
    values?: Record<string, any>,
  ) => intl.formatMessage({ id, defaultMessage }, values);

  const running = run?.status === 'RUNNING';
  const scanned = run?.scannedCount ?? 0;
  const processed = run?.handled ?? 0;
  // The denominator is "replicas scanned this round"; it updates as the round progresses.
  // Leave it blank until known to avoid division by zero
  const percent =
    scanned > 0
      ? Math.min(100, Math.round((processed / scanned) * 100))
      : undefined;

  const details = (run?.detail || []).filter(Boolean);

  return (
    <Modal
      open={open}
      title={t('pages.admin.settings.skillSync.title', 'Sync from Skill Hub')}
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
                    : t('pages.admin.settings.skillSync.scanning', 'Scanning…')
                }
              />
              <div style={{ marginTop: 8, color: 'rgba(0,0,0,0.45)' }}>
                {t(
                  'pages.admin.settings.skillSync.hint',
                  'Processed {done} / {total} copies · {updated} updated · {skipped} skipped',
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
                t(
                  'pages.admin.settings.skillSync.skipped',
                  'Nothing was executed',
                )
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
                  'pages.admin.settings.skillSync.failed',
                  'Sync failed, check backend logs',
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
                'pages.admin.settings.skillSync.stale',
                'This record has been running for too long; the process may have died. Check backend logs.',
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
                label={t(
                  'pages.admin.settings.skillSync.scanned',
                  'Scanned copies',
                )}
              >
                {run.scannedCount ?? 0}
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.skillSync.updated', 'Updated')}
              >
                <Tag color={(run.updated ?? 0) > 0 ? 'green' : 'default'}>
                  {run.updated ?? 0}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item
                label={t(
                  'pages.admin.settings.skillSync.skippedCount',
                  'Not updated',
                )}
              >
                {run.skipped ?? 0}
              </Descriptions.Item>
              <Descriptions.Item
                label={t('pages.admin.settings.skillSync.elapsed', 'Elapsed')}
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
              rowKey={(row: { copyId: number; reason: string }) =>
                `${row.copyId}-${row.reason}`
              }
              dataSource={details}
              columns={[
                {
                  title: t('pages.admin.settings.skillSync.copyId', 'Copy ID'),
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
                  title: t('pages.admin.settings.skillSync.reason', 'Reason'),
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
                'All copies are already up to date.',
              )}
            />
          )}
        </>
      ) : (
        <Alert
          type="info"
          showIcon
          message={t(
            'pages.admin.settings.skillSync.submitting',
            'Submitting sync task…',
          )}
        />
      )}
    </Modal>
  );
}

/** Different colors per reason: source gone / unpublished needs human action, while version-not-ahead is normal. */
function reasonColor(reason: string) {
  if (reason.startsWith('ERROR')) return 'red';
  if (reason === 'SOURCE_GONE' || reason === 'NOT_PUBLIC') return 'orange';
  return 'default';
}

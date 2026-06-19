import { App, Button, Input, Modal } from 'antd';
import { DatabaseOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { formatTime } from '@/utils/format';
import { useStyles } from '../../style';
import { agentApi } from '@/services/agentSphere/api';

interface MemoryModalProps {
  open: boolean;
  onClose: () => void;
  sessionId: number | undefined;
  summary: string;
  onSummaryChange: (v: string) => void;
  runs: any[];
}

export default function MemoryModal({
  open, onClose, sessionId, summary, onSummaryChange, runs,
}: MemoryModalProps) {
  const intl = useIntl();
  const { message } = App.useApp();
  const { styles } = useStyles();

  return (
    <Modal
      title={intl.formatMessage({ id: 'pages.chat.sessionMemory', defaultMessage: 'Session Memory' })}
      open={open}
      onCancel={onClose}
      footer={null}
      width={640}
    >
      <div className={styles.memorySection}>
        <strong>{intl.formatMessage({ id: 'pages.chat.summary', defaultMessage: 'Summary:' })}</strong>
        <Input.TextArea
          rows={3}
          value={summary}
          onChange={(e) => onSummaryChange(e.target.value)}
          className={styles.memoryTextarea}
          maxLength={5000}
        />
        <Button
          size="small"
          type="primary"
          icon={<DatabaseOutlined />}
          className={styles.memoryButton}
          onClick={async () => {
            if (sessionId) {
              await agentApi.sessions.updateSummary(sessionId, summary);
              message.success(intl.formatMessage({ id: 'pages.chat.saveSummary', defaultMessage: 'Summary saved' }));
            }
          }}
        >
          {intl.formatMessage({ id: 'pages.chat.saveSummary', defaultMessage: 'Save Summary' })}
        </Button>
      </div>
      <div>
        <strong>{intl.formatMessage({ id: 'pages.chat.recentRuns', defaultMessage: 'Recent Runs:' })}</strong>
        {runs.length === 0 ? (
          <p className={styles.emptyText}>
            {intl.formatMessage({ id: 'pages.chat.noRuns', defaultMessage: 'No runs found.' })}
          </p>
        ) : (
          <div className={styles.scrollContainer}>
            {runs.map((r: any) => (
              <div key={r.id} className={styles.runItem}>
                <div>
                  <strong>{intl.formatMessage({ id: 'pages.chat.runLabel', defaultMessage: 'Run #{id}' }, { id: r.id })}</strong>
                  <span className={styles.runTitle}>{formatTime(r.createdAt)}</span>
                </div>
                <div className={styles.runPreview}>
                  <span style={{ fontWeight: 500 }}>User:</span> {r.userMessage?.substring(0, 100)}
                </div>
                {r.assistantReply && (
                  <div className={styles.runReply}>
                    <span style={{ fontWeight: 500 }}>Assist:</span> {r.assistantReply.substring(0, 100)}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </Modal>
  );
}

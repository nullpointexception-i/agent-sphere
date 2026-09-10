import { useIntl } from '@umijs/max';
import { Modal } from 'antd';
import { formatTokens } from '../Usage';

interface DetailModalProps {
  open: boolean;
  record: any;
  onClose: () => void;
}

export default function DetailModal({
  open,
  record,
  onClose,
}: DetailModalProps) {
  const intl = useIntl();

  if (!record) return null;

  const isLLM = record.activityType === 'llm_interaction';

  const formatBody = (body: string | undefined) => {
    if (!body) return '-';
    try {
      return JSON.stringify(JSON.parse(body), null, 2);
    } catch {
      return body;
    }
  };

  return (
    <Modal
      title={intl.formatMessage({
        id: 'pages.chat.detail',
        defaultMessage: 'Detail',
      })}
      open={open}
      onCancel={onClose}
      width={800}
      footer={null}
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div style={{ display: 'flex', gap: 16 }}>
          <div>
            <strong>Type: </strong>
            {isLLM ? record.interactionType : record.toolName}
          </div>
          <div>
            <strong>Status: </strong>
            {isLLM ? (record.success ? 'OK' : 'FAIL') : record.toolStatus}
          </div>
        </div>

        {isLLM && (
          <>
            {record.totalTokens != null && (
              <div
                style={{
                  background: '#fafafa',
                  border: '1px solid #f0f0f0',
                  borderRadius: 4,
                  padding: '8px 12px',
                  fontSize: 12,
                  color: '#595959',
                  display: 'flex',
                  gap: 20,
                  flexWrap: 'wrap',
                }}
              >
                <span>Prompt: {formatTokens(record.promptTokens)}</span>
                <span>Completion: {formatTokens(record.completionTokens)}</span>
                <span>
                  <strong>Total: {formatTokens(record.totalTokens)}</strong>
                </span>
                {record.cacheHitTokens != null && (
                  <>
                    <span>
                      Cache hit: {formatTokens(record.cacheHitTokens)}
                    </span>
                    <span>
                      Cache miss: {formatTokens(record.cacheMissTokens)}
                    </span>
                  </>
                )}
              </div>
            )}
            <div>
              <div
                style={{ fontWeight: 600, marginBottom: 4, color: '#8c8c8c' }}
              >
                {intl.formatMessage({
                  id: 'pages.chat.requestBody',
                  defaultMessage: 'Request Body',
                })}
              </div>
              <pre
                style={{
                  background: '#f5f5f5',
                  padding: 12,
                  borderRadius: 4,
                  fontSize: 12,
                  maxHeight: 300,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  margin: 0,
                }}
              >
                {formatBody(record.requestBody)}
              </pre>
            </div>
            <div>
              <div
                style={{ fontWeight: 600, marginBottom: 4, color: '#8c8c8c' }}
              >
                {intl.formatMessage({
                  id: 'pages.chat.responseBody',
                  defaultMessage: 'Response Body',
                })}
              </div>
              <pre
                style={{
                  background: '#f5f5f5',
                  padding: 12,
                  borderRadius: 4,
                  fontSize: 12,
                  maxHeight: 300,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  margin: 0,
                }}
              >
                {formatBody(record.responseBody)}
              </pre>
            </div>
          </>
        )}

        {!isLLM && (
          <>
            <div>
              <div
                style={{ fontWeight: 600, marginBottom: 4, color: '#8c8c8c' }}
              >
                Arguments
              </div>
              <pre
                style={{
                  background: '#f5f5f5',
                  padding: 12,
                  borderRadius: 4,
                  fontSize: 12,
                  maxHeight: 300,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  margin: 0,
                }}
              >
                {formatBody(record.argumentsJson)}
              </pre>
            </div>
            {record.artifact && (
              <div>
                <div
                  style={{ fontWeight: 600, marginBottom: 4, color: '#8c8c8c' }}
                >
                  Result
                </div>
                <pre
                  style={{
                    background: '#f5f5f5',
                    padding: 12,
                    borderRadius: 4,
                    fontSize: 12,
                    maxHeight: 300,
                    overflow: 'auto',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                    margin: 0,
                  }}
                >
                  {formatBody(record.artifact)}
                </pre>
              </div>
            )}
          </>
        )}

        {(isLLM ? record.llmErrorMessage : record.toolErrorMessage) && (
          <div>
            <div style={{ fontWeight: 600, marginBottom: 4, color: '#ff4d4f' }}>
              {intl.formatMessage({
                id: 'pages.chat.errorMessage',
                defaultMessage: 'Error',
              })}
            </div>
            <pre
              style={{
                background: '#fff2f0',
                padding: 12,
                borderRadius: 4,
                fontSize: 12,
                maxHeight: 200,
                overflow: 'auto',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
                margin: 0,
              }}
            >
              {isLLM ? record.llmErrorMessage : record.toolErrorMessage}
            </pre>
          </div>
        )}
      </div>
    </Modal>
  );
}

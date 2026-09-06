import XMarkdown from '@ant-design/x-markdown';
import { Modal, Tabs, Tag, Typography } from 'antd';
import type { SubAgentLive, SubAgentTimelineItem } from './subAgentTypes';

interface SubAgentDetailModalProps {
  open: boolean;
  title: string;
  timeline: SubAgentTimelineItem[];
  /** 实时子 Agent（SSE 聚合）；提供时优先展示实时数据。 */
  live?: SubAgentLive | null;
  onClose: () => void;
}

const STREAMING_IDLE = { hasNextChunk: false, enableAnimation: false };

/** 子 Agent 详情浮层：按时间线分「Model Reply / Tool Calls / Model Reason」三段（主 Agent 样式）。 */
export default function SubAgentDetailModal({
  open,
  title,
  timeline,
  live,
  onClose,
}: SubAgentDetailModalProps) {
  // 实时模式：直接利用 live 聚合的 reasoning/reply/toolCalls；历史模式：timeLine
  let interactions = timeline.filter(
    (t) => t.activityType === 'llm_interaction',
  );
  let toolCalls = timeline.filter((t) => t.activityType === 'tool_call');
  if (live) {
    interactions = [
      {
        activityType: 'llm_interaction',
        reasoning: live.reasoning,
        reply: live.reply,
      } as SubAgentTimelineItem,
    ];
    toolCalls = live.toolCalls.map((tc) => ({
      activityType: 'tool_call' as const,
      toolName: tc.toolName,
      argumentsJson: tc.args,
      artifact: tc.artifact,
      toolStatus: tc.status,
      stepId: 0,
    }));
  }
  const reasoningAll = interactions
    .map((i) => i.reasoning || '')
    .filter((s) => s.trim())
    .join('\n\n');
  const replyAll = interactions
    .map((i) => i.reply || '')
    .filter((s) => s.trim())
    .join('\n\n');

  return (
    <Modal
      title={title || '子 Agent 详情'}
      open={open}
      onCancel={onClose}
      footer={null}
      width={680}
    >
      <Tabs
        defaultActiveKey="reply"
        items={[
          {
            key: 'reply',
            label: 'Model Reply',
            children: (
              <div style={{ maxHeight: 320, overflowY: 'auto' }}>
                {replyAll ? (
                  <XMarkdown streaming={STREAMING_IDLE}>{replyAll}</XMarkdown>
                ) : (
                  <Typography.Text type="secondary">
                    （无文本回复）
                  </Typography.Text>
                )}
              </div>
            ),
          },
          {
            key: 'tool',
            label: `Tool Calls (${toolCalls.length})`,
            children: (
              <div style={{ maxHeight: 320, overflowY: 'auto' }}>
                {toolCalls.length === 0 ? (
                  <Typography.Text type="secondary">
                    （无工具调用）
                  </Typography.Text>
                ) : (
                  toolCalls.map((t, i) => (
                    <div
                      // biome-ignore lint/suspicious/noArrayIndexKey: live items may share stepId, index disambiguates
                      key={`${t.stepId ?? t.interactionId}-${i}`}
                      style={{
                        border: '1px solid #f0f0f0',
                        borderRadius: 6,
                        padding: 8,
                        marginBottom: 8,
                        background: '#fafafa',
                      }}
                    >
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 6,
                        }}
                      >
                        <Typography.Text strong>{t.toolName}</Typography.Text>
                        <Tag
                          color={
                            t.toolStatus === 'SUCCEEDED' || !t.toolStatus
                              ? 'green'
                              : 'red'
                          }
                        >
                          {t.toolStatus || 'PENDING'}
                        </Tag>
                      </div>
                      {t.argumentsJson && (
                        <pre
                          style={{
                            margin: '6px 0 0',
                            fontSize: 11,
                            whiteSpace: 'pre-wrap',
                            maxHeight: 140,
                            overflowY: 'auto',
                            background: '#fff',
                            padding: 6,
                            borderRadius: 4,
                          }}
                        >
                          {t.argumentsJson}
                        </pre>
                      )}
                      {t.artifact && (
                        <div
                          style={{
                            marginTop: 6,
                            fontSize: 12,
                            color: '#595959',
                          }}
                        >
                          <Typography.Text type="secondary">
                            结果：
                          </Typography.Text>
                          <pre
                            style={{
                              margin: '4px 0 0',
                              fontSize: 11,
                              whiteSpace: 'pre-wrap',
                              maxHeight: 140,
                              overflowY: 'auto',
                              background: '#fff',
                              padding: 6,
                              borderRadius: 4,
                            }}
                          >
                            {t.artifact}
                          </pre>
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>
            ),
          },
          {
            key: 'reason',
            label: 'Model Reason',
            children: (
              <div
                style={{
                  maxHeight: 320,
                  overflowY: 'auto',
                  fontSize: 13,
                  color: '#8c8c8c',
                  fontStyle: 'italic',
                  borderLeft: '2px solid #d9d9d9',
                  paddingLeft: 8,
                }}
              >
                {reasoningAll ? (
                  <XMarkdown streaming={STREAMING_IDLE}>
                    {reasoningAll}
                  </XMarkdown>
                ) : (
                  <Typography.Text type="secondary">
                    （无推理内容）
                  </Typography.Text>
                )}
              </div>
            ),
          },
        ]}
      />
    </Modal>
  );
}

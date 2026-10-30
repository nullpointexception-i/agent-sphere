import { useIntl } from '@umijs/max';
import {
  Button,
  Checkbox,
  Input,
  Radio,
  Space,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import ChatBottomSheet from './ChatBottomSheet';

export interface ClarificationItem {
  clarificationId: string;
  runId: number;
  title?: string;
  type?: string;
  multiple?: boolean;
  options?: string;
  description?: string;
}

export interface ClarificationBatch {
  runId: number;
  items: ClarificationItem[];
}

interface Answer {
  selected: string[];
  text: string;
}

interface Props {
  open: boolean;
  batch: ClarificationBatch | null;
  submitting?: boolean;
  bottomOffset?: number;
  onResolve: (
    answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
  ) => void;
  onCancel?: () => void;
}

function parseOptions(raw?: string): Array<{ label: string; value: string }> {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .map((o: any) => {
        if (typeof o === 'string') return { label: o, value: o };
        const value = String(o?.value ?? o?.label ?? '');
        return { label: String(o?.label ?? value), value };
      })
      .filter((o) => o.value);
  } catch {
    return [];
  }
}

export default function ClarificationDrawer({
  open,
  batch,
  submitting,
  bottomOffset = 0,
  onResolve,
  onCancel,
}: Props) {
  const intl = useIntl();
  const [answers, setAnswers] = useState<Record<string, Answer>>({});

  useEffect(() => {
    setAnswers({});
  }, [batch?.items?.map((item) => item.clarificationId).join('|')]);

  const items = batch?.items || [];
  const allAnswered =
    items.length > 0 &&
    items.every((item) => {
      const answer = answers[item.clarificationId];
      return Boolean(answer?.selected?.length || answer?.text?.trim());
    });

  const setAnswer = (id: string, patch: Partial<Answer>) => {
    setAnswers((prev) => ({
      ...prev,
      [id]: {
        selected: patch.selected ?? prev[id]?.selected ?? [],
        text: patch.text ?? prev[id]?.text ?? '',
      },
    }));
  };

  const tabs = useMemo(
    () =>
      items.map((item) => {
        const answer = answers[item.clarificationId] || { selected: [], text: '' };
        const options = parseOptions(item.options);
        const type = item.type || 'input';
        return {
          key: item.clarificationId,
          label: (
            <Space size={4}>
              <span>{item.title || item.clarificationId}</span>
              {answer.selected.length || answer.text.trim() ? (
                <Tag color="blue">
                  {intl.formatMessage({
                    id: 'pages.chat.clarification.answered',
                    defaultMessage: 'Answered',
                  })}
                </Tag>
              ) : null}
            </Space>
          ),
          children: (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <Typography.Text strong>
                {item.title || item.clarificationId}
              </Typography.Text>
              {item.description ? (
                <Typography.Paragraph
                  style={{ whiteSpace: 'pre-wrap', marginBottom: 0, color: '#595959' }}
                >
                  {item.description}
                </Typography.Paragraph>
              ) : null}
              {type === 'confirm' ? (
                <Radio.Group
                  value={answer.selected[0]}
                  onChange={(e) =>
                    setAnswer(item.clarificationId, { selected: [e.target.value] })
                  }
                >
                  <Space direction="vertical">
                    <Radio value="confirmed">
                      {intl.formatMessage({
                        id: 'pages.chat.clarification.confirm',
                        defaultMessage: 'Confirm',
                      })}
                    </Radio>
                    <Radio value="cancelled">
                      {intl.formatMessage({
                        id: 'pages.chat.clarification.cancelOption',
                        defaultMessage: 'Cancel',
                      })}
                    </Radio>
                  </Space>
                </Radio.Group>
              ) : null}
              {type === 'choice' && options.length > 0 ? (
                item.multiple ? (
                  <Checkbox.Group
                    value={answer.selected}
                    onChange={(values) =>
                      setAnswer(item.clarificationId, {
                        selected: values.map(String),
                      })
                    }
                    options={options}
                  />
                ) : (
                  <Radio.Group
                    value={answer.selected[0]}
                    onChange={(e) =>
                      setAnswer(item.clarificationId, {
                        selected: [e.target.value],
                      })
                    }
                    options={options}
                  />
                )
              ) : null}
              <Input.TextArea
                value={answer.text}
                onChange={(e) =>
                  setAnswer(item.clarificationId, { text: e.target.value })
                }
                autoSize={{ minRows: 2, maxRows: 5 }}
                placeholder={intl.formatMessage({
                  id: 'pages.chat.clarification.inputPlaceholder',
                  defaultMessage: 'Type another answer (optional)',
                })}
              />
            </div>
          ),
        };
      }),
    [answers, intl, items],
  );

  const submit = () => {
    if (!allAnswered) return;
    onResolve(
      items.map((item) => ({
        clarificationId: item.clarificationId,
        selected: answers[item.clarificationId]?.selected || [],
        text: answers[item.clarificationId]?.text || '',
      })),
    );
  };

  return (
    <ChatBottomSheet
      open={open}
      bottomOffset={bottomOffset}
      title={
        <Space size={8}>
          <Typography.Text strong>
            {intl.formatMessage({
              id: 'pages.chat.clarification.title',
              defaultMessage: 'Your input is needed',
            })}
          </Typography.Text>
          <Tag>
            {intl.formatMessage(
              {
                id: 'pages.chat.clarification.selected',
                defaultMessage: '{selected}/{total} answered',
              },
              { selected: Object.values(answers).filter((a) => a.selected.length || a.text.trim()).length, total: items.length },
            )}
          </Tag>
        </Space>
      }
      footer={
        <>
          <Button danger disabled={submitting || items.length === 0} onClick={onCancel}>
            {intl.formatMessage({
              id: 'pages.chat.clarification.cancel',
              defaultMessage: 'Cancel run',
            })}
          </Button>
          <Button
            type="primary"
            loading={submitting}
            disabled={!allAnswered}
            onClick={submit}
          >
            {intl.formatMessage({
              id: 'pages.chat.clarification.submit',
              defaultMessage: 'Submit',
            })}
          </Button>
        </>
      }
    >
      <Tabs items={tabs} />
    </ChatBottomSheet>
  );
}

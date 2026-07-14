import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  CopyOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  RobotOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Bubble } from '@ant-design/x';
import type { BubbleItemType } from '@ant-design/x/es/bubble/interface';
import XMarkdown from '@ant-design/x-markdown';
import { Avatar, Button, Card, Input, Radio, Space, Tag, Typography } from 'antd';
import '@ant-design/x-markdown/es/XMarkdown/index.css';

import hljs from 'highlight.js';
import 'highlight.js/styles/github.css';
import { useIntl } from '@umijs/max';
import { useEffect, useMemo, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { useStyles } from '../../style';

interface MessageListProps {
  messages: any[];
  collapsedKeys: Set<string>;
  onCollapsedKeysChange: (keys: Set<string>) => void;
}

const COLLAPSE_THRESHOLD = 300;
const CODE_LIKE_PATTERN =
  /^(package |import |public |private |protected |class |interface |function |const |let |var |def |fun |#include|#define|<!DOCTYPE|<html|<svg|<xml)/m;
const STREAMING_IDLE = { hasNextChunk: false, enableAnimation: false };

export default function MessageList({
  messages,
  collapsedKeys,
  onCollapsedKeysChange,
}: MessageListProps) {
  const intl = useIntl();
  const { styles } = useStyles();

  useEffect(() => {
    const id = 'oc-shuttle-keyframes';
    if (document.getElementById(id)) return;
    const style = document.createElement('style');
    style.id = id;
    style.textContent = `
      @keyframes ocShuttle {
        0%   { transform: translateX(0); }
        20%  { transform: translateX(26px); }
        50%  { transform: translateX(26px); }
        70%  { transform: translateX(0); }
        100% { transform: translateX(0); }
      }
    `;
    document.head.appendChild(style);
    return () => style.remove();
  }, []);

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
  };

  const toggleCollapse = (key: string) => {
    const next = new Set(collapsedKeys);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    onCollapsedKeysChange(next);
  };

  const maybeWrapCode = (content: string): string => {
    if (content.includes('```') || content.length < 20) return content;
    if (content.includes('\n') && CODE_LIKE_PATTERN.test(content)) {
      return '```java\n' + content + '\n```';
    }
    return content;
  };

  const markdownComponents = useMemo(
    () => ({
      code: ({ children, lang, className, inline, block }: any) => {
        const text = Array.isArray(children)
          ? children.join('')
          : String(children || '');
        const language =
          lang ||
          (className?.startsWith('language-') ? className.slice(9) : '');
        const isBlock = block !== undefined ? block : !inline;
        if (!isBlock) {
          return (
            <code
              style={{
                background: '#f0f0f0',
                padding: '2px 6px',
                borderRadius: 4,
                fontSize: '0.9em',
              }}
            >
              {children}
            </code>
          );
        }
        const preStyle: React.CSSProperties = {
          background: '#f6f8fa',
          padding: 16,
          borderRadius: 8,
          fontSize: 13,
          lineHeight: 1.5,
          margin: 0,
          whiteSpace: 'pre',
          overflowX: 'auto',
          overflowY: 'hidden',
          fontFamily:
            'ui-monospace,SFMono-Regular,SF Mono,Menlo,Consolas,monospace',
        };
        try {
          const highlighted =
            language && hljs.getLanguage(language)
              ? hljs.highlight(text, { language: language }).value
              : hljs.highlightAuto(text).value;
          return (
            <pre style={preStyle}>
              <code dangerouslySetInnerHTML={{ __html: highlighted }} />
            </pre>
          );
        } catch {
          return (
            <pre style={preStyle}>
              <code>{children}</code>
            </pre>
          );
        }
      },
      table: ({ children }: any) => (
        <div style={{ overflowX: 'auto', margin: '8px 0' }}>
          <table
            style={{
              borderCollapse: 'collapse',
              width: '100%',
              border: '1px solid #d9d9d9',
            }}
          >
            {children}
          </table>
        </div>
      ),
      th: ({ children }: any) => (
        <th
          style={{
            border: '1px solid #d9d9d9',
            padding: '8px 12px',
            textAlign: 'left',
            background: '#f5f5f5',
            fontWeight: 600,
          }}
        >
          {children}
        </th>
      ),
      td: ({ children }: any) => (
        <td
          style={{
            border: '1px solid #d9d9d9',
            padding: '8px 12px',
            textAlign: 'left',
          }}
        >
          {children}
        </td>
      ),
    }),
    [],
  );

  const roleConfig = useMemo(
    () => ({
      user: {
        placement: 'end' as const,
        avatar: <Avatar icon={<UserOutlined />} />,
        styles: { content: { background: '#e6f7e6', color: '#333' } },
        contentRender: (content: string) => {
          if (!content) return undefined;
          return (
            <div className={styles.markdown}>
              <XMarkdown
                streaming={STREAMING_IDLE}
                components={markdownComponents}
              >
                {maybeWrapCode(content)}
              </XMarkdown>
            </div>
          );
        },
      },
      ai: {
        placement: 'start' as const,
        variant: 'borderless',
        styles: { content: { maxWidth: '100%' } },
        avatar: (
          <Avatar
            icon={<RobotOutlined />}
            style={{ background: '#e6f4ff', color: '#1677ff' }}
          />
        ),
        contentRender: (content: string) => {
          if (!content) return undefined;
          return (
            <div className={styles.markdown}>
              <XMarkdown
                streaming={STREAMING_IDLE}
                components={markdownComponents}
              >
                {content}
              </XMarkdown>
            </div>
          );
        },
      },
      reasoning: () => ({
        placement: 'start' as const,
        avatar: null,
        contentRender: (content: string) => {
          if (!content) return null;
          return (
            <div
              style={{
                fontSize: 13,
                color: '#8c8c8c',
                fontStyle: 'italic',
                borderLeft: '2px solid #d9d9d9',
                paddingLeft: 8,
                margin: '4px 0',
              }}
            >
              <div
                style={{
                  fontSize: 11,
                  fontWeight: 500,
                  color: '#bfbfbf',
                  marginBottom: 2,
                }}
              >
                {intl.formatMessage({
                  id: 'chat.reasoning.label',
                  defaultMessage: 'Reasoning',
                })}
              </div>
              <XMarkdown
                streaming={STREAMING_IDLE}
                components={markdownComponents}
              >
                {content}
              </XMarkdown>
            </div>
          );
        },
      }),
      'reasoning-collapsed': () => ({
        placement: 'start' as const,
        avatar: null,
      }),
    }),
    [markdownComponents, intl, styles.markdown],
  );

  const bubbleItems = useMemo<BubbleItemType[]>(() => {
    const visible = messages.filter(
      (m: any) => (m.content && m.content !== '{}') || (m.clarifications && m.clarifications.length > 0),
    );
    const visibleItems = visible
      .map((m: any, idx: number) => {
        const key = (m as any)._reasoningId
          ? `reasoning-${(m as any)._reasoningId}`
          : m.runId
            ? `run-${m.runId}`
            : `msg-${idx}`;
        const isLong = m.content && m.content.length > COLLAPSE_THRESHOLD;
        const isCollapsed = collapsedKeys.has(key);
        const isReasoning = m.role === 'reasoning';
        if (isReasoning) {
          if (isCollapsed) {
            return {
              key,
              role: 'reasoning-collapsed' as const,
              content: (
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => toggleCollapse(key)}
                >
                  <EyeOutlined />{' '}
                  {intl.formatMessage({
                    id: 'chat.reasoning.show',
                    defaultMessage: 'Show thinking',
                  })}
                </Button>
              ),
            };
          }
          const elapsed = m.ts ? Math.floor((Date.now() - m.ts) / 1000) : 0;
          return {
            key,
            role: 'reasoning',
            content: m.content,
            footer: (
              <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{ fontSize: 11, color: '#bfbfbf', minWidth: 36 }}>
                  ⏱️ {elapsed}s
                </span>
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => toggleCollapse(key)}
                >
                  <EyeInvisibleOutlined />{' '}
                  {intl.formatMessage({
                    id: 'chat.reasoning.hide',
                    defaultMessage: 'Hide thinking',
                  })}
                </Button>
                <Button
                  type="text"
                  size="small"
                  icon={<CopyOutlined />}
                  onClick={() => handleCopy(m.content)}
                />
              </div>
            ),
          };
        }
        const item: BubbleItemType = {
          key,
          role: m.role,
          content:
            isLong && isCollapsed
              ? m.content.substring(0, COLLAPSE_THRESHOLD) + '...'
              : m.content,
          footer: (
            <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              {(m as any)._pending && (
                <span
                  style={{
                    position: 'relative',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 3,
                    width: 32,
                    height: 14,
                  }}
                >
                  {[0, 1, 2, 3].map((i) => (
                    <span
                      key={i}
                      style={{
                        width: 4,
                        height: 4,
                        borderRadius: '50%',
                        background: '#d9d9d9',
                      }}
                    />
                  ))}
                  <span
                    style={{
                      position: 'absolute',
                      top: 1,
                      left: 0,
                      width: 6,
                      height: 6,
                      borderRadius: 2,
                      background: '#1677ff',
                      animation: 'ocShuttle 1.5s ease-in-out infinite',
                    }}
                  />
                </span>
              )}
              {isLong && (
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => toggleCollapse(key)}
                >
                  {isCollapsed
                    ? intl.formatMessage({
                        id: 'chat.showMore',
                        defaultMessage: 'Show more',
                      })
                    : intl.formatMessage({
                        id: 'chat.showLess',
                        defaultMessage: 'Show less',
                      })}
                </Button>
              )}
              <Button
                type="text"
                size="small"
                icon={<CopyOutlined />}
                onClick={() => handleCopy(m.content)}
              />
            </div>
          ),
        };
        if (m.clarifications && m.clarifications.length > 0) {
          const clarifications = m.clarifications;
          item.contentRender = (_content: string) => (
            <div>
              {_content && (
                <div className={styles.markdown}>
                  <XMarkdown streaming={STREAMING_IDLE} components={markdownComponents}>
                    {_content}
                  </XMarkdown>
                </div>
              )}
              {clarifications.map((c: any) => (
                <ClarificationCard
                  key={c.clarificationId || `${c.runId}-${c.title}`}
                  clarification={c}
                  onRespond={(resp) => {
                    agentApi.sessions.clarify(c.sessionId, c.runId, resp, c.clarificationId).catch(() => {});
                  }}
                />
              ))}
            </div>
          );
        }
        return item;
      })
      .filter(Boolean) as BubbleItemType[];

    return visibleItems;
  }, [messages, collapsedKeys, intl]);

  return (
    <>
      <Bubble.List
        items={bubbleItems}
        role={roleConfig}
        autoScroll
        styles={{ root: { maxWidth: 940 } }}
      />
    </>
  );
}

function ClarificationCard({
  clarification,
  onRespond,
}: {
  clarification: any;
  onRespond: (response: string) => void;
}) {
  const [optimisticValue, setOptimisticValue] = useState<string | null>(null);
  const [inputVal, setInputVal] = useState('');
  const intl = useIntl();

  const isExpired = clarification.status === 'expired';
  const isResolved = clarification.status === 'responded' || optimisticValue !== null;
  const userValue = optimisticValue ?? clarification.userResponse;

  // Derive display label for the responded value
  const resolvedLabel = useMemo(() => {
    if (!userValue) return '';
    if (clarification.type === 'choice' && clarification.options?.length) {
      const opt = clarification.options.find(
        (o: any) => o.value === userValue || o.label === userValue,
      );
      return opt?.label || userValue;
    }
    if (clarification.type === 'confirm') {
      return userValue === 'confirmed'
        ? '✅ ' + intl.formatMessage({ id: 'pages.chat.clarify.confirmed', defaultMessage: 'Confirmed' })
        : '❌ ' + intl.formatMessage({ id: 'pages.chat.clarify.cancelled', defaultMessage: 'Cancelled' });
    }
    return userValue;
  }, [userValue, clarification.type, clarification.options, intl]);

  if (isExpired) {
    return (
      <Card size="small" style={{ margin: '8px 0', opacity: 0.5 }}>
        <Typography.Text type="secondary">
          {clarification.title || 'Clarification request'} —{' '}
          <Tag color="default">{intl.formatMessage({ id: 'pages.chat.clarify.expired', defaultMessage: 'Expired' })}</Tag>
        </Typography.Text>
      </Card>
    );
  }

  if (isResolved) {
    return (
      <Card size="small" style={{ margin: '8px 0', background: '#f6ffed', borderLeft: '3px solid #52c41a' }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space>
            <CheckCircleOutlined style={{ color: '#52c41a' }} />
            <Typography.Text strong>{clarification.title}</Typography.Text>
          </Space>
          {resolvedLabel && (
            <Typography.Text style={{ marginLeft: 22, color: '#555' }}>
              {resolvedLabel}
            </Typography.Text>
          )}
        </Space>
      </Card>
    );
  }

  const handleAction = (value: string) => {
    setOptimisticValue(value);
    onRespond(value);
  };

  if (clarification.type === 'choice' && clarification.options?.length) {
    return (
      <Card
        size="small"
        title={clarification.title}
        style={{ margin: '8px 0', borderLeft: '3px solid #1677ff' }}
      >
        <Radio.Group onChange={(e) => handleAction(e.target.value)} value={undefined}>
          <Space direction="vertical" style={{ width: '100%' }}>
            {clarification.options.map((opt: any, i: number) => (
              <Radio.Button key={i} value={opt.value || opt.label} style={{ width: '100%', textAlign: 'left', height: 'auto', whiteSpace: 'normal', padding: '8px 16px' }}>
                <div>
                  <div style={{ fontWeight: 500 }}>{opt.label}</div>
                  {opt.description && (
                    <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 2 }}>{opt.description}</div>
                  )}
                </div>
              </Radio.Button>
            ))}
          </Space>
        </Radio.Group>
      </Card>
    );
  }

  if (clarification.type === 'input') {
    return (
      <Card
        size="small"
        title={clarification.title}
        style={{ margin: '8px 0', borderLeft: '3px solid #1677ff' }}
      >
        <Space.Compact style={{ width: '100%' }}>
          <Input
            value={inputVal}
            onChange={(e) => setInputVal(e.target.value)}
            placeholder={intl.formatMessage({ id: 'pages.chat.clarify.inputPlaceholder', defaultMessage: 'Type your response...' })}
            onPressEnter={() => inputVal.trim() && handleAction(inputVal.trim())}
          />
          <Button
            type="primary"
            disabled={!inputVal.trim()}
            onClick={() => inputVal.trim() && handleAction(inputVal.trim())}
          >
            {intl.formatMessage({ id: 'pages.save', defaultMessage: 'Submit' })}
          </Button>
        </Space.Compact>
      </Card>
    );
  }

  return (
    <Card
      size="small"
      title={clarification.title}
      style={{ margin: '8px 0', borderLeft: '3px solid #1677ff' }}
    >
      <Space>
        <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => handleAction('confirmed')}>
          {intl.formatMessage({ id: 'pages.chat.clarify.confirm', defaultMessage: 'Confirm' })}
        </Button>
        <Button danger icon={<CloseCircleOutlined />} onClick={() => handleAction('cancelled')}>
          {intl.formatMessage({ id: 'pages.chat.clarify.cancel', defaultMessage: 'Cancel' })}
        </Button>
      </Space>
    </Card>
  );
}

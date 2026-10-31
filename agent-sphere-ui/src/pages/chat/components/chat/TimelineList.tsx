import {
  ArrowRightOutlined,
  BulbOutlined,
  CheckCircleOutlined,
  CheckOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  CopyOutlined,
  DislikeFilled,
  DislikeOutlined,
  DownOutlined,
  FileTextOutlined,
  GlobalOutlined,
  LikeFilled,
  LikeOutlined,
  LoadingOutlined,
  MessageOutlined,
  PauseCircleOutlined,
  ReadOutlined,
  RightOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  ShareAltOutlined,
  StopOutlined,
  SyncOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import XMarkdown from '@ant-design/x-markdown';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Checkbox,
  Divider,
  Image,
  Input,
  Modal,
  Radio,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { useStyles } from '../../style';
import UsageChip, { formatTokens } from '../Usage';
import { stripSubAgentMarkerPrefix } from './subAgentMarker';
import type { SubAgentLiveMap } from './subAgentTypes';
import {
  browserResult,
  cliView,
  docSummary,
  docSummaryZh,
  extractTodos,
  genericSummary,
  isBrowserTool,
  progressView,
  readToolResultView,
  searchResultView,
  skillView,
  type TodoItem,
  type ToolFamily,
  textContainsView,
  todoProgress,
  todoTotal,
  toolCallReason,
  toolFamily,
  toolResultText,
  webPageView,
} from './toolRenderers';

interface TimelineListProps {
  rows: any[];
  hasMore: boolean;
  sessionId?: number;
  onLoadOlder: () => void;
  onRespondClarify?: (c: any, resp: string) => void;
  onCancelClarification?: (row: any) => void;
  loadSubAgentSteps?: (subId: number) => Promise<any[]>;
  subAgentLive?: SubAgentLiveMap;
  preparingTool?: { publishId?: string; name?: string } | null;
}

const KIND_LABEL: Record<string, string> = {
  user: '用户',
  assistant: '助手',
  tool: '工具',
  clarification: '澄清',
  tool_approval: '工具授权',
  subagent: '子 Agent',
  compaction: '压缩',
  run_status: '状态',
  error: '错误',
};

// 内部协议工具：finish 的答案已作为助手正文展示，ask_clarification 由澄清行展示。
const FINISH_TOOL_NAME = 'builtin_12';
const ASK_CLARIFICATION_TOOL_NAME = 'builtin_7';

function isInternalToolRow(row: any): boolean {
  if (!row || row.kind !== 'tool') {
    return false;
  }
  const content = row.content;
  return (
    content?.toolName === FINISH_TOOL_NAME ||
    content?.toolName === ASK_CLARIFICATION_TOOL_NAME ||
    content?.displayName === '完成任务' ||
    content?.displayName === 'Finish' ||
    content?.displayName === '请求澄清' ||
    content?.displayName === 'Ask Clarification'
  );
}

function formatDuration(ms?: number) {
  if (ms == null || Number.isNaN(ms) || ms < 0) return '';
  if (ms < 1000) return '<1s';
  const totalSec = Math.round(ms / 1000);
  if (totalSec < 60) return `${totalSec}s`;
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return s ? `${m}m${s}s` : `${m}m`;
}

/** 助手 RUNNING 状态：来回晃动的小球（终态时该位不渲染，直接消失）。 */
function RunningDot() {
  const { styles } = useStyles();
  return <span className={styles.runningDot} />;
}

/** 一键复制：点击复制到剪切板，提示"已复制"，图标切对勾后还原。 */
function CopyButton({ text }: { text: string }) {
  const { message } = App.useApp();
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | null>(null);
  const onCopy = () => {
    if (!text) return;
    void navigator.clipboard
      ?.writeText(text)
      .then(() => {
        setCopied(true);
        message.success('已复制到剪切板');
        if (timer.current) window.clearTimeout(timer.current);
        timer.current = window.setTimeout(() => setCopied(false), 2000);
      })
      .catch(() => message.error('复制失败'));
  };
  useEffect(() => {
    return () => {
      if (timer.current) window.clearTimeout(timer.current);
    };
  }, []);
  return (
    <Button
      type="text"
      size="small"
      icon={copied ? <CheckOutlined /> : <CopyOutlined />}
      onClick={onCopy}
      title="复制"
      style={{
        width: 24,
        height: 24,
        padding: 0,
        border: '1px solid #e5e7eb',
        borderRadius: 6,
        background: '#fff',
        color: '#8c8c8c',
      }}
    />
  );
}

function StateTag({ state, small }: { state?: string; small?: boolean }) {
  const color =
    state === 'COMPLETED' || state === 'succeeded' || state === 'ANSWERED'
      ? 'success'
      : state === 'FAILED' ||
          state === 'failed' ||
          state === 'CANCELLED' ||
          state === 'TIMEOUT'
        ? 'error'
        : 'processing';
  return (
    <Tag
      color={color}
      style={
        small ? { margin: 0, fontSize: 11, color: '#8c8c8c' } : { margin: 0 }
      }
    >
      {state || 'RUNNING'}
    </Tag>
  );
}

/** 自持折叠状态的可展开块：点击只切当前块，不再涉及整卡收起；传 open/onToggle 时为受控；faint 弱化（工具卡用）。 */
function Block({
  defaultOpen = false,
  title,
  children,
  open,
  onToggle,
  faint = false,
}: {
  defaultOpen?: boolean;
  title: string;
  children: React.ReactNode;
  open?: boolean;
  onToggle?: () => void;
  faint?: boolean;
}) {
  const [selfOpen, setSelfOpen] = useState(defaultOpen);
  const isOpen = open !== undefined ? open : selfOpen;
  const toggle = () => (onToggle ? onToggle() : setSelfOpen((o) => !o));
  return (
    <div style={{ marginTop: 4 }}>
      <Button
        type="link"
        size="small"
        onClick={toggle}
        style={{
          padding: 0,
          fontSize: faint ? 11 : 12,
          color: faint ? '#999' : undefined,
        }}
      >
        {isOpen ? <DownOutlined /> : <RightOutlined />} {title}
      </Button>
      {isOpen && <div style={{ marginTop: 4 }}>{children}</div>}
    </div>
  );
}

function AssistantCard({ row, sessionId, isLatest }: any) {
  const intl = useIntl();
  const { message } = App.useApp();
  const content = row.content || {};
  const thinking =
    typeof content.thinking === 'string' ? content.thinking.trim() : '';
  const reply = typeof content.reply === 'string' ? content.reply.trim() : '';
  const hasThinking = thinking.length > 0 && thinking !== '{}';
  const hasReply = reply.length > 0 && reply !== '{}';
  const isRunning = !row.state || row.state === 'RUNNING';
  const [thinkingOpen, setThinkingOpen] = useState(
    Boolean(isLatest && !hasReply && isRunning),
  );
  const [feedback, setFeedback] = useState<string | undefined>(
    content.feedback,
  );
  const [shareOpen, setShareOpen] = useState(false);
  const [shareCount, setShareCount] = useState(3);
  const thinkingBoxRef = useRef<HTMLPreElement | null>(null);
  useEffect(() => {
    setFeedback(content.feedback);
  }, [content.feedback]);
  useEffect(() => {
    if (hasReply || !isLatest || !isRunning) setThinkingOpen(false);
  }, [hasReply, isLatest, isRunning]);
  useEffect(() => {
    if (!thinkingOpen) return;
    const el = thinkingBoxRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [thinking, thinkingOpen]);

  const seconds =
    content.durationMs != null && Number(content.durationMs) > 0
      ? Math.max(1, Math.round(Number(content.durationMs) / 1000))
      : null;
  const isThinking = hasThinking && !hasReply;
  const interactionId = row.refInteractionId;
  const submitFeedback = async (value: 'LIKE' | 'DISLIKE') => {
    if (!sessionId || interactionId == null) return;
    const next = feedback === value ? 'NONE' : value;
    const previous = feedback;
    setFeedback(next === 'NONE' ? undefined : next);
    try {
      await agentApi.sessions.feedback(
        sessionId,
        interactionId,
        row.runId,
        next,
      );
    } catch (e: any) {
      setFeedback(previous);
      message.error(e?.message || 'Feedback failed');
    }
  };
  const share = async () => {
    if (!sessionId || row.runId == null) return;
    try {
      const res = await agentApi.sessions.share(
        sessionId,
        row.runId,
        shareCount,
      );
      if (res?.shareToken) {
        setShareOpen(false);
        window.open(
          `${window.location.origin}/share/${res.shareToken}`,
          '_blank',
          'noopener,noreferrer',
        );
      }
    } catch (e: any) {
      message.error(e?.message || 'Share failed');
    }
  };

  return (
    <div>
      {hasThinking && (
        <div>
          <button
            type="button"
            onClick={() => setThinkingOpen((v) => !v)}
            style={{
              padding: 0,
              border: 'none',
              background: 'transparent',
              color: '#8c8c8c',
              fontWeight: 600,
              fontSize: 12,
              cursor: 'pointer',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
            }}
          >
            {isThinking
              ? intl.formatMessage({
                  id: 'pages.chat.assistant.thinking',
                  defaultMessage: 'Thinking',
                })
              : seconds != null
                ? intl.formatMessage(
                    {
                      id: 'pages.chat.assistant.thoughtForSeconds',
                      defaultMessage: 'Thought for {seconds}s',
                    },
                    { seconds },
                  )
                : intl.formatMessage({
                    id: 'pages.chat.assistant.thought',
                    defaultMessage: 'Thought',
                  })}
            {thinkingOpen ? <DownOutlined /> : <RightOutlined />}
          </button>
          {thinkingOpen && (
            <pre
              ref={thinkingBoxRef}
              style={{
                whiteSpace: 'pre-wrap',
                fontSize: 12,
                color: '#8c8c8c',
                maxHeight: 240,
                overflow: 'auto',
                marginTop: 4,
              }}
            >
              {thinking}
            </pre>
          )}
        </div>
      )}
      {hasReply && (
        <div style={{ fontSize: 13 }}>
          <XMarkdown content={reply} />
        </div>
      )}
      {(hasReply || hasThinking) && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            marginTop: 6,
          }}
        >
          <Button
            type="text"
            size="small"
            icon={feedback === 'LIKE' ? <LikeFilled /> : <LikeOutlined />}
            onClick={() => submitFeedback('LIKE')}
            title={intl.formatMessage({
              id: 'pages.chat.assistant.like',
              defaultMessage: 'Like',
            })}
          />
          <Button
            type="text"
            size="small"
            icon={
              feedback === 'DISLIKE' ? <DislikeFilled /> : <DislikeOutlined />
            }
            onClick={() => submitFeedback('DISLIKE')}
            title={intl.formatMessage({
              id: 'pages.chat.assistant.dislike',
              defaultMessage: 'Dislike',
            })}
          />
          <Button
            type="text"
            size="small"
            icon={<ShareAltOutlined />}
            onClick={() => setShareOpen(true)}
            title={intl.formatMessage({
              id: 'pages.chat.assistant.share',
              defaultMessage: 'Share',
            })}
          />
          {hasReply && <CopyButton text={reply} />}
        </div>
      )}
      <UsageChip usage={content.usage} />
      <Modal
        open={shareOpen}
        title={intl.formatMessage({
          id: 'pages.chat.assistant.shareTitle',
          defaultMessage: 'Share conversation',
        })}
        onCancel={() => setShareOpen(false)}
        onOk={share}
        okText={intl.formatMessage({
          id: 'pages.chat.assistant.shareConfirm',
          defaultMessage: 'Create link',
        })}
        cancelText={intl.formatMessage({
          id: 'pages.chat.assistant.cancel',
          defaultMessage: 'Cancel',
        })}
      >
        <div style={{ marginBottom: 8, color: '#8c8c8c' }}>
          {intl.formatMessage({
            id: 'pages.chat.assistant.shareCountHint',
            defaultMessage: 'Choose how many conversation turns to share',
          })}
        </div>
        <Radio.Group
          value={shareCount}
          onChange={(e) => setShareCount(e.target.value)}
        >
          <Radio.Button value={1}>1</Radio.Button>
          <Radio.Button value={3}>3</Radio.Button>
          <Radio.Button value={5}>5</Radio.Button>
          <Radio.Button value={10}>10</Radio.Button>
        </Radio.Group>
      </Modal>
    </div>
  );
}

const TODO_STYLE: Record<
  string,
  {
    color?: string;
    strikethrough?: boolean;
    muted?: boolean;
    indeterminate?: boolean;
  }
> = {
  completed: { color: '#389e0d', strikethrough: true },
  in_progress: { color: '#d46b08', indeterminate: true },
  pending: {},
  cancelled: { color: '#8c8c8c', strikethrough: true, muted: true },
};

const PRIORITY_ORDER: Record<string, number> = { high: 0, medium: 1, low: 2 };

function sortTodos(todos: TodoItem[]): TodoItem[] {
  return [...todos].sort(
    (a, b) =>
      (PRIORITY_ORDER[a.priority ?? ''] ?? 9) -
      (PRIORITY_ORDER[b.priority ?? ''] ?? 9),
  );
}

function formatToolDuration(ms?: number) {
  if (typeof ms !== 'number' || ms < 0) return '';
  if (ms < 1000) return `${ms}ms`;
  const seconds = ms / 1000;
  if (seconds < 60) {
    return `${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)}s`;
  }
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m${Math.round(seconds % 60)}s`;
}

/** 超过该耗时视为慢工具（红色 + ⚠）。 */
const SLOW_TOOL_MS = 5000;

/** 工具耗时：超过 5s 视为慢工具，红色 + ⚠ 提醒。 */
function ToolDuration({ ms }: { ms?: number }) {
  const text = formatToolDuration(ms);
  if (!text || typeof ms !== 'number') return null;
  const slow = ms >= SLOW_TOOL_MS;
  return (
    <div
      title={slow ? '慢工具' : '工具耗时'}
      style={{
        marginTop: 4,
        fontSize: 10.5,
        color: slow ? '#cf1322' : '#a0a0a0',
        userSelect: 'none',
      }}
    >
      {slow ? `⚠ ${text}` : text}
    </div>
  );
}

/** 长内容预览：正文超过阈值时提供弹窗，避免把整页正文挤进时间线。 */
function ContentPreviewButton({
  title,
  content,
}: {
  title: string;
  content?: string | null;
}) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const [open, setOpen] = useState(false);
  if (!content || content.length < 200) return null;
  return (
    <>
      <Button
        type="link"
        size="small"
        style={{ padding: 0, height: 'auto', fontSize: 11 }}
        onClick={(event) => {
          event.stopPropagation();
          setOpen(true);
        }}
      >
        {isEn
          ? `Preview (${content.length.toLocaleString()} chars)`
          : `预览全文（${content.length.toLocaleString()} 字符）`}
      </Button>
      <Modal
        open={open}
        title={title}
        footer={null}
        width={760}
        onCancel={() => setOpen(false)}
      >
        <pre
          style={{
            margin: 0,
            maxHeight: '60vh',
            overflow: 'auto',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            fontSize: 12,
            lineHeight: 1.6,
          }}
        >
          {content}
        </pre>
      </Modal>
    </>
  );
}

/** 参数 + 结果收口为一个折叠块，避免每个工具占两行裸 JSON。 */
function RawDataBlock({ content, label }: { content: any; label?: string }) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  return (
    <details style={{ marginTop: 5 }}>
      <summary
        style={{
          cursor: 'pointer',
          fontSize: 10.5,
          color: '#a0a0a0',
          userSelect: 'none',
        }}
      >
        {label || (isEn ? 'Raw data' : '查看原始数据')}
      </summary>
      <div style={{ marginTop: 4, fontSize: 10, color: '#a0a0a0' }}>
        {isEn ? 'Arguments' : '参数'}
      </div>
      <pre
        style={{
          whiteSpace: 'pre-wrap',
          fontSize: 10.5,
          color: '#999',
          maxHeight: 120,
          overflow: 'auto',
          margin: '2px 0 0',
        }}
      >
        {prettyJsonSafe(content.args)}
      </pre>
      <div style={{ marginTop: 4, fontSize: 10, color: '#a0a0a0' }}>
        {isEn ? 'Result' : '结果'}
      </div>
      <pre
        style={{
          whiteSpace: 'pre-wrap',
          fontSize: 10.5,
          color: '#999',
          maxHeight: 120,
          overflow: 'auto',
          margin: '2px 0 0',
        }}
      >
        {prettyJsonSafe(content.artifact)}
      </pre>
    </details>
  );
}

function TodoCard({ content }: { content: any }) {
  const intl = useIntl();
  const todos = extractTodos(content);
  const realTotal = todoTotal(content);
  const { done } = todoProgress(todos);
  const total = Math.max(realTotal, todos.length);
  const sorted = sortTodos(todos);
  const summaryChip =
    total > 0 ? (
      <Tag
        style={{
          fontSize: 10,
          lineHeight: '16px',
          color: done === total ? '#389e0d' : '#d46b08',
        }}
      >
        {done}/{total} 已完成
      </Tag>
    ) : null;
  return (
    <div
      style={{
        marginTop: 4,
        borderRadius: 8,
        padding: '8px 12px',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          marginBottom: 4,
        }}
      >
        <span style={{ fontSize: 12, opacity: 0.7 }}>☑️</span>
        <Typography.Text style={{ fontSize: 12, color: '#8c8c8c' }}>
          {intl.formatMessage({
            id: 'pages.chat.tool.todoProgress',
            defaultMessage: 'Todo progress',
          })}
        </Typography.Text>
        {summaryChip}
      </div>
      {todos.length === 0 ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {intl.formatMessage({
            id: 'pages.chat.tool.noTodos',
            defaultMessage: 'No todo items',
          })}
        </Typography.Text>
      ) : (
        sorted.map((t, i) => {
          const st = TODO_STYLE[t.status ?? ''] ?? TODO_STYLE.pending;
          return (
            <div
              key={`${t.content}-${t.status ?? ''}`}
              data-todo-index={i}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                padding: '2px 0',
                opacity: st.muted ? 0.55 : 1,
              }}
            >
              <Checkbox
                checked={t.status === 'completed'}
                indeterminate={st.indeterminate}
                disabled
              />
              <Typography.Text
                style={{
                  fontSize: 12,
                  color: st.color || undefined,
                  textDecoration: st.strikethrough ? 'line-through' : undefined,
                }}
              >
                {t.content}
              </Typography.Text>
              {t.priority === 'high' && (
                <Tag
                  color="red"
                  style={{ fontSize: 9, lineHeight: '15px', marginLeft: 4 }}
                >
                  high
                </Tag>
              )}
              {(t.skillId != null || t.stepId) && (
                <Tag
                  color="purple"
                  style={{ fontSize: 9, lineHeight: '15px', marginLeft: 4 }}
                  title={
                    t.skillId != null && t.stepId
                      ? `技能 #${t.skillId} · 步骤 ${t.stepId}`
                      : t.skillId != null
                        ? `技能 #${t.skillId}`
                        : `步骤 ${t.stepId}`
                  }
                >
                  {t.skillId != null ? `skill#${t.skillId}` : ''}
                  {t.stepId ? ` ${t.stepId}` : ''}
                </Tag>
              )}
            </div>
          );
        })
      )}
      <RawDataBlock content={content} />
    </div>
  );
}

function DocCard({ content }: { content: any }) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const summary = isEn ? docSummary(content) : docSummaryZh(content);
  const artifact = content.artifact;
  let artifactObj: any = null;
  try {
    artifactObj = artifact ? JSON.parse(artifact) : null;
  } catch {
    artifactObj = null;
  }
  let argsObj: any = null;
  try {
    argsObj = content.args ? JSON.parse(content.args) : null;
  } catch {
    argsObj = null;
  }
  const meta = content.meta || {};
  const documentId =
    meta.documentId != null ? meta.documentId : artifactObj?.documentId;
  const contentChars =
    typeof meta.chars === 'number'
      ? meta.chars
      : typeof artifactObj?.content === 'string'
        ? artifactObj.content.length
        : typeof argsObj?.content === 'string'
          ? argsObj.content.length
          : null;
  const totalLines =
    meta.totalLines != null
      ? meta.totalLines
      : typeof artifactObj?.totalLines === 'number'
        ? artifactObj.totalLines
        : null;
  const version = meta.version != null ? meta.version : artifactObj?.version;
  const documentTitle =
    [meta.title, artifactObj?.title, argsObj?.title]
      .find((value) => typeof value === 'string' && value.trim())
      ?.trim() || '';
  const documentContent =
    typeof artifactObj?.content === 'string'
      ? artifactObj.content
      : typeof argsObj?.content === 'string'
        ? argsObj.content
        : '';
  return (
    <div style={{ marginTop: 4 }}>
      <div
        style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}
      >
        <FileTextOutlined style={{ fontSize: 12, color: '#8c8c8c' }} />
        <Typography.Text
          style={{ fontSize: 12, color: '#8c8c8c', flexShrink: 0 }}
        >
          {content.displayName ||
            intl.formatMessage({
              id: 'pages.chat.tool.docOperation',
              defaultMessage: 'Document operation',
            })}
        </Typography.Text>
        <ArrowRightOutlined
          style={{ fontSize: 11, color: '#a0a0a0', flexShrink: 0 }}
        />
        <Typography.Text
          strong
          title={summary}
          style={{
            fontSize: 12,
            minWidth: 0,
            maxWidth: 420,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {summary}
        </Typography.Text>
      </div>
      {(documentId != null || contentChars != null) && (
        <div style={{ marginTop: 4 }}>
          <Typography.Text
            type="secondary"
            style={{ fontSize: 11, lineHeight: '16px' }}
          >
            {[
              documentId != null ? `documentId=${documentId}` : null,
              version != null ? `v${version}` : null,
              contentChars != null
                ? isEn
                  ? `${contentChars.toLocaleString()} chars`
                  : `正文 ${contentChars.toLocaleString()} 字符`
                : null,
              totalLines != null
                ? isEn
                  ? `${totalLines} lines`
                  : `${totalLines} 行`
                : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </Typography.Text>
          {(artifactObj?.preview || documentTitle) && (
            <Typography.Text
              type="secondary"
              style={{ display: 'block', fontSize: 11, lineHeight: '16px' }}
            >
              {documentTitle || (isEn ? 'Document' : '文档')}
              {artifactObj.preview
                ? `：${String(artifactObj.preview).slice(0, 60)}…`
                : ''}
            </Typography.Text>
          )}
          <div>
            <ContentPreviewButton
              title={documentTitle || (isEn ? 'Document' : '文档')}
              content={documentContent}
            />
          </div>
        </div>
      )}
    </div>
  );
}

function GenericCard({
  content,
  family,
  hideJson = false,
  stateLabel,
}: {
  content: any;
  family: ToolFamily;
  hideJson?: boolean;
  stateLabel?: string;
}) {
  const intl = useIntl();
  const summary = genericSummary(content);
  const displayName =
    (intl.locale === 'en-US'
      ? content.displayNameEn || content.displayName
      : content.displayName || content.displayNameEn) ||
    (family === 'todo' ? '待办写入' : content.toolName) ||
    intl.formatMessage({ id: 'pages.chat.tool.tool', defaultMessage: 'Tool' });
  return (
    <div style={{ marginTop: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <ToolOutlined style={{ fontSize: 12, color: '#8c8c8c' }} />
        <Typography.Text strong style={{ fontSize: 12, color: '#8c8c8c' }}>
          {displayName}
        </Typography.Text>
        {summary !== displayName && (
          <>
            <ArrowRightOutlined style={{ fontSize: 10, color: '#a0a0a0' }} />
            <Typography.Text style={{ fontSize: 12 }}>
              {summary}
            </Typography.Text>
          </>
        )}
        {stateLabel && (
          <span
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 3,
              color: '#ff4d4f',
            }}
          >
            <CloseOutlined style={{ fontSize: 10 }} />
            <Typography.Text style={{ fontSize: 12, color: '#ff4d4f' }}>
              {stateLabel}
            </Typography.Text>
          </span>
        )}
      </div>
      {!hideJson && <RawDataBlock content={content} />}
    </div>
  );
}

function ToolCardBody({ row }: any) {
  const intl = useIntl();
  const content = row.content || {};
  if (/^mcp_\d+_\d+$/.test(content.toolName || '')) {
    return <McpToolCard content={content} state={row.state} />;
  }
  if (
    content.toolName === 'builtin_10' ||
    content.displayName === '等待' ||
    content.displayName === 'Wait'
  ) {
    return <WaitCard content={content} state={row.state} />;
  }
  if (
    content.toolName === 'builtin_13' ||
    content.displayName === '当前时间' ||
    content.displayName === 'Current Time'
  ) {
    return <CurrentTimeCard content={content} state={row.state} />;
  }
  if (
    content.toolName === 'builtin_11' ||
    content.displayName === '文本包含判断' ||
    content.displayName === 'Text Contains'
  ) {
    return <TextContainsCard content={content} state={row.state} />;
  }
  if (
    content.toolName === 'builtin_16' ||
    content.displayName === '报告进度' ||
    content.displayName === 'Progress'
  ) {
    return <ProgressCard content={content} state={row.state} />;
  }
  if (
    content.toolName === 'builtin_4' ||
    content.displayName === '网页读取' ||
    content.displayName === 'Web Read'
  ) {
    return <WebPageCard content={content} state={row.state} kind="read" />;
  }
  if (
    content.toolName === 'builtin_2' ||
    content.displayName === '网页获取' ||
    content.displayName === 'Web Fetch'
  ) {
    return <WebPageCard content={content} state={row.state} kind="fetch" />;
  }
  if (
    content.toolName === 'builtin_8' ||
    content.displayName === '技能库' ||
    content.displayName === 'Skill Library'
  ) {
    return <SkillCard content={content} state={row.state} kind="library" />;
  }
  if (
    content.toolName === 'builtin_9' ||
    content.displayName === '技能管理' ||
    content.displayName === 'Skill Manage'
  ) {
    return <SkillCard content={content} state={row.state} kind="manage" />;
  }
  if (
    content.toolName === 'builtin_14' ||
    /回读工具结果|read tool result/i.test(content.displayName || '')
  ) {
    return <ReadToolResultCard content={content} state={row.state} />;
  }
  if (/^cli_\d+$/.test(content.toolName || '')) {
    return <CliCard content={content} state={row.state} />;
  }
  if (
    content.toolName === 'builtin_5' ||
    isBrowserTool(content, content.displayName)
  ) {
    return <BrowserCard content={content} state={row.state} />;
  }
  const family = toolFamily(content, content.displayName);
  const name =
    (intl.locale === 'en-US'
      ? content.displayNameEn || content.displayName
      : content.displayName || content.displayNameEn) ||
    row.title ||
    intl.formatMessage({ id: 'pages.chat.tool.tool', defaultMessage: 'Tool' });
  // 「委派子 Agent」工具调用在下方已由 SubAgentCard 完整展示，顶部标题行冗余，隐藏。
  const isDelegate = /委派|子 ?agent/i.test(name);
  // 浏览器工具：后端把超时/失败编码进 artifact（row.state 恒为 SUCCEEDED），此处按 artifact 解析真实状态。
  const isBrowser = isBrowserTool(content, content.displayName);
  const isSkillLibrary =
    content.toolName === 'builtin_8' || /技能库|skill library/i.test(name);
  const brow = browserResult(content, content.displayName);
  const stateLabel = brow.failed
    ? brow.errorCategory === 'timeout'
      ? 'timeout'
      : 'failed'
    : undefined;
  // 浏览器/doc：顶部标题行冗余，由 GenericCard/DocCard 自身渲染，这里隐藏。
  const hideTitleRow = isDelegate || family === 'doc' || isBrowser;
  return (
    <div>
      {!hideTitleRow && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <ToolOutlined style={{ fontSize: 12, color: '#8c8c8c' }} />
          <Typography.Text strong style={{ fontSize: 12, color: '#8c8c8c' }}>
            {name}
          </Typography.Text>
          {family !== 'todo' && <StateTag state={row.state} small />}
        </div>
      )}
      {Array.isArray(content.images) && content.images.length > 0 && (
        <UserImages images={content.images} />
      )}
      {family === 'todo' ? (
        <TodoCard content={content} />
      ) : family === 'doc' ? (
        <DocCard content={content} />
      ) : (
        <GenericCard
          content={content}
          family={family}
          hideJson={isDelegate || isBrowser || isSkillLibrary}
          stateLabel={stateLabel}
        />
      )}
    </div>
  );
}

function ToolCard({ row }: any) {
  const intl = useIntl();
  const duration = row?.content?.meta?.durationMs;
  const reason = toolCallReason(row?.content || {});
  return (
    <div>
      <ToolCardBody row={row} />
      {reason && (
        <div
          title={reason}
          style={{
            marginTop: 4,
            maxWidth: 560,
            fontSize: 11,
            lineHeight: '16px',
            color: '#8c8c8c',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {intl.locale === 'en-US' ? 'Reason: ' : '理由：'}
          {reason.slice(0, 120)}
        </div>
      )}
      <ToolDuration ms={typeof duration === 'number' ? duration : undefined} />
    </div>
  );
}

function WaitCard({ content, state }: any) {
  const intl = useIntl();
  let seconds: number | null = null;
  try {
    const args = content.args ? JSON.parse(content.args) : null;
    if (args?.ms != null) {
      seconds = Math.max(1, Math.round(Number(args.ms) / 1000));
    }
  } catch {
    // ignore malformed args
  }
  const normalizedState = String(state ?? '').toLowerCase();
  const failed = normalizedState === 'failed' || normalizedState === 'error';
  const completed =
    !failed && ['succeeded', 'completed', 'success'].includes(normalizedState);
  const labelId = failed
    ? 'pages.chat.tool.waitFailed'
    : completed
      ? 'pages.chat.tool.waitCompleted'
      : 'pages.chat.tool.waitRunning';
  const defaultLabel = failed
    ? 'Wait failed'
    : completed
      ? 'Wait completed'
      : 'Waiting';
  const color = failed ? '#cf1322' : completed ? '#389e0d' : '#8c8c8c';
  return (
    <div
      style={{ marginTop: 4, display: 'flex', alignItems: 'center', gap: 6 }}
    >
      <ClockCircleOutlined style={{ fontSize: 12, color }} />
      <Typography.Text strong style={{ fontSize: 12, color }}>
        {intl.formatMessage({
          id: labelId,
          defaultMessage: defaultLabel,
        })}
      </Typography.Text>
      {seconds != null && !failed && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {intl.formatMessage(
            {
              id: 'pages.chat.tool.waitForSeconds',
              defaultMessage: '{seconds}s',
            },
            { seconds },
          )}
        </Typography.Text>
      )}
    </div>
  );
}

function McpToolCard({ content, state }: any) {
  const intl = useIntl();
  const name =
    content.displayName ||
    content.toolName ||
    intl.formatMessage({
      id: 'pages.chat.tool.tool',
      defaultMessage: 'Tool',
    });
  const server =
    typeof content.meta?.server === 'string' ? content.meta.server : '';
  const results = searchResultView(content);
  let action = '';
  let resultStatus = '';
  try {
    const args = content.args ? JSON.parse(content.args) : null;
    action = args?.action || args?.operation || '';
  } catch {
    // ignore
  }
  try {
    const artifact = content.artifact ? JSON.parse(content.artifact) : null;
    resultStatus =
      artifact?.status ||
      artifact?.data?.status ||
      artifact?.data?.action ||
      artifact?.action ||
      '';
  } catch {
    if (typeof content.artifact === 'string' && content.artifact.length <= 40) {
      resultStatus = content.artifact;
    }
  }
  return (
    <div>
      <div
        style={{
          marginTop: 4,
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
        }}
      >
        <ToolOutlined style={{ fontSize: 12, color: '#8c8c8c' }} />
        <Typography.Text strong style={{ fontSize: 12, color: '#8c8c8c' }}>
          {name}
        </Typography.Text>
        {server && (
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
            · {server}
          </Typography.Text>
        )}
        {action && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            · {action}
          </Typography.Text>
        )}
        {resultStatus && (
          <Tag style={{ margin: 0, fontSize: 10 }} color="blue">
            {String(resultStatus)}
          </Tag>
        )}
        <StateTag state={state} small />
      </div>
      {results.length > 0 && (
        <div
          style={{
            marginTop: 5,
            display: 'flex',
            flexDirection: 'column',
            gap: 4,
          }}
        >
          {results.map((result) => (
            <div
              key={result.url || result.title}
              style={{ fontSize: 11, lineHeight: '16px', minWidth: 0 }}
            >
              <div
                title={result.url || result.title}
                style={{
                  color: '#1677ff',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {result.title}
              </div>
              <div
                title={result.summary}
                style={{
                  color: '#a0a0a0',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {[result.host, result.summary].filter(Boolean).join(' · ')}
              </div>
            </div>
          ))}
        </div>
      )}
      <div>
        <ContentPreviewButton title={name} content={toolResultText(content)} />
      </div>
      <RawDataBlock content={content} />
    </div>
  );
}

function CurrentTimeCard({ content, state }: any) {
  const intl = useIntl();
  let time = '';
  let timezone = '';
  try {
    const artifact = content.artifact ? JSON.parse(content.artifact) : null;
    time = typeof artifact?.time === 'string' ? artifact.time : '';
    timezone = typeof artifact?.timezone === 'string' ? artifact.timezone : '';
  } catch {
    // ignore malformed artifact
  }
  const display = time
    ? time
        .replace('T', ' ')
        .replace(/([+-]\d{2}:\d{2})$/, '')
        .slice(0, 16)
    : '';
  return (
    <div
      style={{ marginTop: 4, display: 'flex', alignItems: 'center', gap: 6 }}
    >
      <ClockCircleOutlined style={{ fontSize: 12, color: '#8c8c8c' }} />
      <Typography.Text strong style={{ fontSize: 12, color: '#8c8c8c' }}>
        {intl.formatMessage({
          id: 'pages.chat.tool.currentTime',
          defaultMessage: 'Current time',
        })}
      </Typography.Text>
      {display && (
        <>
          <Typography.Text type="secondary">·</Typography.Text>
          <Typography.Text style={{ fontSize: 12 }}>{display}</Typography.Text>
        </>
      )}
      {timezone && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {timezone}
        </Typography.Text>
      )}
      <StateTag state={state} small />
    </div>
  );
}

const TEXT_MATCH_MODE_LABEL: Record<string, { zh: string; en: string }> = {
  EXACT: { zh: '精确匹配', en: 'exact' },
  IGNORE_CASE: { zh: '忽略大小写', en: 'ignore case' },
  TRIMMED: { zh: '去首尾空白', en: 'trimmed' },
  IGNORE_CASE_TRIMMED: {
    zh: '忽略大小写 + 去空白',
    en: 'ignore case + trimmed',
  },
};

function prettyJsonSafe(raw?: string) {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/** 文本包含判断：结果优先，参数只保留 needle/mode/长度，避免两个裸 JSON 折叠块。 */
function TextContainsCard({ content, state }: any) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const view = textContainsView(content);
  const mode = TEXT_MATCH_MODE_LABEL[view.mode] || {
    zh: view.mode,
    en: view.mode.toLowerCase(),
  };
  const failed = view.error != null;
  const matched = !failed && view.hit === true;
  const unknown = !failed && view.hit === null;
  const accent = failed
    ? '#cf1322'
    : matched
      ? '#389e0d'
      : unknown
        ? '#1677ff'
        : '#8c8c8c';
  const background = failed
    ? '#fff1f0'
    : matched
      ? '#f6ffed'
      : unknown
        ? '#f0f5ff'
        : '#fafafa';
  const showState = failed || String(state ?? '').toLowerCase() !== 'succeeded';
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background,
        maxWidth: 560,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
        }}
      >
        <SearchOutlined style={{ fontSize: 12, color: accent }} />
        <Typography.Text strong style={{ fontSize: 12, color: '#595959' }}>
          {isEn ? 'Text contains' : '文本包含判断'}
        </Typography.Text>
        <Tag
          color={matched ? 'green' : unknown ? 'blue' : 'default'}
          style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}
        >
          {failed
            ? isEn
              ? 'failed'
              : '失败'
            : matched
              ? isEn
                ? 'matched'
                : '命中'
              : unknown
                ? isEn
                  ? 'unknown'
                  : '无结果'
                : isEn
                  ? 'not found'
                  : '未命中'}
        </Tag>
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {mode[isEn ? 'en' : 'zh']}
        </Typography.Text>
        {showState && <StateTag state={state} small />}
      </div>
      {failed && (
        <Typography.Text
          type="danger"
          style={{ display: 'block', marginTop: 4, fontSize: 11 }}
        >
          {view.error}
        </Typography.Text>
      )}
      {(view.needle || view.haystackChars != null) && (
        <div
          style={{
            marginTop: 5,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            minWidth: 0,
            fontSize: 11,
            color: '#8c8c8c',
          }}
        >
          {view.needle && (
            <>
              <span style={{ flexShrink: 0 }}>{isEn ? 'needle' : '查找'}</span>
              <Typography.Text
                code
                title={view.needle}
                style={{
                  fontSize: 11,
                  maxWidth: 280,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {view.needle}
              </Typography.Text>
            </>
          )}
          {view.haystackChars != null && (
            <span style={{ flexShrink: 0 }}>
              · {isEn ? 'target' : '目标'} {view.haystackChars.toLocaleString()}{' '}
              {isEn ? 'chars' : '字符'}
            </span>
          )}
        </div>
      )}
      <details style={{ marginTop: 5 }}>
        <summary
          style={{
            cursor: 'pointer',
            fontSize: 10.5,
            color: '#a0a0a0',
            userSelect: 'none',
          }}
        >
          {isEn ? 'Raw data' : '查看原始数据'}
        </summary>
        <div style={{ marginTop: 4, fontSize: 10, color: '#a0a0a0' }}>
          {isEn ? 'Arguments' : '参数'}
        </div>
        <pre
          style={{
            whiteSpace: 'pre-wrap',
            fontSize: 10.5,
            color: '#999',
            maxHeight: 120,
            overflow: 'auto',
            margin: '2px 0 0',
          }}
        >
          {prettyJsonSafe(content.args)}
        </pre>
        <div style={{ marginTop: 4, fontSize: 10, color: '#a0a0a0' }}>
          {isEn ? 'Result' : '结果'}
        </div>
        <pre
          style={{
            whiteSpace: 'pre-wrap',
            fontSize: 10.5,
            color: '#999',
            maxHeight: 120,
            overflow: 'auto',
            margin: '2px 0 0',
          }}
        >
          {prettyJsonSafe(content.artifact)}
        </pre>
      </details>
    </div>
  );
}

function isSucceededState(state: any) {
  return String(state ?? '').toLowerCase() === 'succeeded';
}

function ProgressCard({ content, state }: any) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const view = progressView(content);
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: '#f0f7ff',
        maxWidth: 560,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <SyncOutlined
          spin={String(state ?? '').toLowerCase() === 'running'}
          style={{ fontSize: 12, color: '#1677ff' }}
        />
        <Typography.Text strong style={{ fontSize: 12, color: '#595959' }}>
          {isEn ? 'Progress' : '报告进度'}
        </Typography.Text>
        {view.percent != null && (
          <Tag
            color="blue"
            style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}
          >
            {view.percent}%
          </Tag>
        )}
        {!isSucceededState(state) && <StateTag state={state} small />}
      </div>
      {view.summary && (
        <div style={{ marginTop: 5, fontSize: 12, color: '#434343' }}>
          {view.summary}
        </div>
      )}
      {view.nextAction && (
        <div style={{ marginTop: 3, fontSize: 11, color: '#8c8c8c' }}>
          {isEn ? 'Next: ' : '下一步：'}
          {view.nextAction}
        </div>
      )}
      {view.percent != null && (
        <div
          style={{
            marginTop: 6,
            height: 4,
            borderRadius: 2,
            background: '#d6e4ff',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              width: `${view.percent}%`,
              height: '100%',
              background: '#1677ff',
            }}
          />
        </div>
      )}
      <RawDataBlock content={content} />
    </div>
  );
}

function WebPageCard({
  content,
  state,
  kind,
}: {
  content: any;
  state?: string;
  kind: 'read' | 'fetch';
}) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const view = webPageView(content);
  let body = '';
  try {
    const artifact = content.artifact ? JSON.parse(content.artifact) : null;
    body =
      typeof artifact?.markdown === 'string'
        ? artifact.markdown
        : typeof artifact?.content === 'string'
          ? artifact.content
          : '';
  } catch {
    // ignore malformed artifact
  }
  let host = view.url;
  try {
    host = new URL(view.url).host || view.url;
  } catch {
    // keep raw url
  }
  const okStatus =
    view.statusCode != null && view.statusCode >= 200 && view.statusCode < 400;
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: view.error ? '#fff1f0' : '#fafafa',
        maxWidth: 560,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
        }}
      >
        <GlobalOutlined
          style={{ fontSize: 12, color: view.error ? '#cf1322' : '#1677ff' }}
        />
        <Typography.Text strong style={{ fontSize: 12, color: '#595959' }}>
          {content.displayName ||
            (kind === 'read'
              ? intl.formatMessage({
                  id: 'pages.chat.tool.webRead',
                  defaultMessage: 'Web read',
                })
              : intl.formatMessage({
                  id: 'pages.chat.tool.webFetch',
                  defaultMessage: 'Web fetch',
                }))}
        </Typography.Text>
        {view.statusCode != null && (
          <Tag
            color={okStatus ? 'green' : 'red'}
            style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}
          >
            {view.statusCode}
          </Tag>
        )}
        {!isSucceededState(state) && <StateTag state={state} small />}
      </div>
      {host && (
        <div
          title={view.url}
          style={{
            marginTop: 4,
            fontSize: 11,
            color: '#1677ff',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {host}
        </div>
      )}
      {view.title && (
        <div style={{ marginTop: 3, fontSize: 12, color: '#434343' }}>
          {view.title}
        </div>
      )}
      {view.error ? (
        <div style={{ marginTop: 4, fontSize: 11, color: '#cf1322' }}>
          {view.error}
        </div>
      ) : (
        view.chars != null && (
          <div style={{ marginTop: 4, fontSize: 11, color: '#8c8c8c' }}>
            {isEn
              ? `${view.chars.toLocaleString()} chars`
              : `正文 ${view.chars.toLocaleString()} 字符`}
          </div>
        )
      )}
      {!view.error && (
        <div>
          <ContentPreviewButton
            title={view.title || host || view.url}
            content={body}
          />
        </div>
      )}
      <RawDataBlock content={content} />
    </div>
  );
}

function SkillCard({
  content,
  state,
  kind,
}: {
  content: any;
  state?: string;
  kind: 'library' | 'manage';
}) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const view = skillView(content);
  const title =
    content.displayName ||
    (kind === 'library'
      ? intl.formatMessage({
          id: 'pages.chat.tool.skillLibrary',
          defaultMessage: 'Skill library',
        })
      : intl.formatMessage({
          id: 'pages.chat.tool.skillManage',
          defaultMessage: 'Skill manage',
        }));
  const summary =
    kind === 'library' && view.total != null
      ? isEn
        ? `${view.total} skills`
        : `${view.total} 个技能`
      : view.action;
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: view.error ? '#fff1f0' : '#fafafa',
        maxWidth: 560,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
        }}
      >
        <ReadOutlined
          style={{ fontSize: 12, color: view.error ? '#cf1322' : '#722ed1' }}
        />
        <Typography.Text strong style={{ fontSize: 12, color: '#595959' }}>
          {title}
        </Typography.Text>
        <Tag
          color="purple"
          style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}
        >
          {summary}
        </Tag>
        {!isSucceededState(state) && <StateTag state={state} small />}
      </div>
      {view.preview && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#8c8c8c' }}>
          {view.preview.slice(0, 180)}
          {view.preview.length > 180 ? '…' : ''}
        </div>
      )}
      {view.names.length > 0 && (
        <div
          style={{ marginTop: 4, display: 'flex', gap: 4, flexWrap: 'wrap' }}
        >
          {view.names.map((name) => (
            <Tag key={name} style={{ margin: 0, fontSize: 10 }}>
              {name}
            </Tag>
          ))}
        </div>
      )}
      {view.error && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#cf1322' }}>
          {view.error}
        </div>
      )}
      <RawDataBlock content={content} />
    </div>
  );
}

function ReadToolResultCard({ content, state }: any) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const view = readToolResultView(content);
  const end = view.nextOffset ?? view.totalChars;
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: view.error ? '#fff1f0' : '#fafafa',
        maxWidth: 560,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
        }}
      >
        <FileTextOutlined
          style={{ fontSize: 12, color: view.error ? '#cf1322' : '#8c8c8c' }}
        />
        <Typography.Text strong style={{ fontSize: 12, color: '#595959' }}>
          {isEn ? 'Read tool result' : '回读工具结果'}
        </Typography.Text>
        {view.callId && (
          <Typography.Text code style={{ fontSize: 10 }}>
            {view.callId}
          </Typography.Text>
        )}
        {!isSucceededState(state) && <StateTag state={state} small />}
      </div>
      {view.totalChars != null ? (
        <div style={{ marginTop: 4, fontSize: 11, color: '#8c8c8c' }}>
          {isEn ? 'chars' : '字符'} {view.offset ?? 0}–{end ?? view.totalChars}{' '}
          / {view.totalChars.toLocaleString()}
          {view.truncated ? (isEn ? ' · truncated' : ' · 已截断') : ''}
        </div>
      ) : (
        !view.error && (
          <div style={{ marginTop: 4, fontSize: 11, color: '#8c8c8c' }}>
            {view.toolName || view.callId}
          </div>
        )
      )}
      {view.error && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#cf1322' }}>
          {view.error}
        </div>
      )}
      <RawDataBlock content={content} />
    </div>
  );
}

function CliCard({ content, state }: any) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const view = cliView(content);
  const output = (view.stderr || view.stdout || '').trim();
  const firstLine = view.firstLine || output.split('\n')[0] || '';
  const ok = view.exitCode === 0 && !view.error;
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: ok ? '#fafafa' : '#fff1f0',
        maxWidth: 560,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
        }}
      >
        <CodeOutlined
          style={{ fontSize: 12, color: ok ? '#8c8c8c' : '#cf1322' }}
        />
        <Typography.Text strong style={{ fontSize: 12, color: '#595959' }}>
          {content.displayName || (isEn ? 'Command' : '命令执行')}
        </Typography.Text>
        {view.exitCode != null && (
          <Tag
            color={view.exitCode === 0 ? 'green' : 'red'}
            style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}
          >
            exit {view.exitCode}
          </Tag>
        )}
        {!isSucceededState(state) && <StateTag state={state} small />}
      </div>
      {firstLine && (
        <div
          title={output}
          style={{
            marginTop: 4,
            fontSize: 11,
            color: ok ? '#595959' : '#cf1322',
            fontFamily: 'monospace',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {firstLine.slice(0, 160)}
        </div>
      )}
      {view.error && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#cf1322' }}>
          {view.error}
        </div>
      )}
      <div>
        <ContentPreviewButton
          title={content.displayName || (isEn ? 'Command output' : '命令输出')}
          content={output}
        />
      </div>
      <RawDataBlock content={content} />
    </div>
  );
}

function BrowserCard({ content, state }: any) {
  const intl = useIntl();
  const isEn = intl.locale === 'en-US';
  const brow = browserResult(content, content.displayName);
  let args: any = {};
  let artifact: any = {};
  try {
    args = content.args ? JSON.parse(content.args) : {};
  } catch {
    // ignore malformed args
  }
  try {
    artifact = content.artifact ? JSON.parse(content.artifact) : {};
  } catch {
    // ignore malformed artifact
  }
  const action =
    typeof content.meta?.action === 'string'
      ? content.meta.action
      : typeof args.action === 'string'
        ? args.action
        : '';
  const url =
    typeof content.meta?.url === 'string'
      ? content.meta.url
      : typeof args.url === 'string'
        ? args.url
        : artifact?.data?.url;
  let host = '';
  try {
    host = url ? new URL(url).host : '';
  } catch {
    host = '';
  }
  const tabId = content.meta?.tabId ?? args.tabId ?? artifact?.data?.tabId;
  const target =
    args.ref != null
      ? `ref #${args.ref}`
      : typeof args.selector === 'string'
        ? args.selector
        : typeof args.text === 'string'
          ? args.text
          : '';
  const method = brow.method || '';
  const resultType = brow.resultType || '';
  const screenshot = artifact?.data?.screenshot || null;
  const screenshotSize =
    screenshot?.width && screenshot?.height
      ? `${screenshot.width}×${screenshot.height}`
      : '';
  const scopeLabel =
    args?.scope === 'full'
      ? isEn
        ? 'full page'
        : '整页'
      : args?.scope === 'viewport'
        ? isEn
          ? 'viewport'
          : '视口'
        : args?.scope;
  const screenshotImages =
    Array.isArray(content.images) && content.images.length > 0
      ? content.images
      : screenshot?.fileKey
        ? [
            {
              fileKey: screenshot.fileKey,
              contentType: screenshot.contentType,
            },
          ]
        : [];
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: brow.failed ? '#fff1f0' : '#fafafa',
        maxWidth: 560,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
        }}
      >
        <GlobalOutlined
          style={{ fontSize: 12, color: brow.failed ? '#cf1322' : '#1677ff' }}
        />
        <Typography.Text strong style={{ fontSize: 12, color: '#595959' }}>
          {isEn ? 'Browser' : '浏览器'}
        </Typography.Text>
        {action && (
          <Tag
            color="blue"
            style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}
          >
            {action}
          </Tag>
        )}
        {brow.failed && brow.errorCategory && (
          <Tag
            color="red"
            style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}
          >
            {brow.errorCategory}
          </Tag>
        )}
        {brow.failed && !brow.errorCategory && (
          <Tag
            color="red"
            style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}
          >
            {isEn ? 'failed' : '失败'}
          </Tag>
        )}
        {!brow.failed && !isSucceededState(state) && (
          <StateTag state={state} small />
        )}
      </div>
      {(host || tabId != null) && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#8c8c8c' }}>
          {host}
          {host && tabId != null ? ' · ' : ''}
          {tabId != null ? `tab ${tabId}` : ''}
        </div>
      )}
      {target && (
        <div
          title={target}
          style={{
            marginTop: 3,
            fontSize: 11,
            color: '#595959',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {target}
        </div>
      )}
      {screenshotImages.length > 0 && (
        <div style={{ marginTop: 6 }}>
          <div style={{ marginBottom: 4, fontSize: 10.5, color: '#a0a0a0' }}>
            {[isEn ? 'Screenshot' : '截图', screenshotSize, scopeLabel]
              .filter(Boolean)
              .join(' · ')}
          </div>
          <UserImages images={screenshotImages} variant="screenshot" />
        </div>
      )}
      {(method || resultType) && (
        <div style={{ marginTop: 3, fontSize: 10.5, color: '#a0a0a0' }}>
          {[method, resultType].filter(Boolean).join(' · ')}
        </div>
      )}
      {brow.warning && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#d46b08' }}>
          ⚠ {brow.warning}
        </div>
      )}
      {brow.errorMessage && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#cf1322' }}>
          {brow.errorMessage}
        </div>
      )}
      <RawDataBlock content={content} />
    </div>
  );
}

function parseJsonArray(raw?: string): any[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function ToolApprovalCard({ row }: any) {
  const intl = useIntl();
  const content = row.content || {};
  const calls = parseJsonArray(content.toolCalls);
  const decisions = parseJsonArray(content.decisions);
  const byCall = new Map<string, string>();
  for (const d of decisions) {
    if (d?.callId) byCall.set(String(d.callId), String(d.decision || 'DENY'));
  }
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <SafetyCertificateOutlined style={{ fontSize: 13, color: '#8c8c8c' }} />
        <Typography.Text strong style={{ fontSize: 12, color: '#8c8c8c' }}>
          {row.state === 'PENDING'
            ? intl.formatMessage({
                id: 'pages.chat.tool.approvalWaiting',
                defaultMessage: 'Waiting for tool approval',
              })
            : row.state === 'CANCELLED'
              ? intl.formatMessage({
                  id: 'pages.chat.tool.approvalCancelled',
                  defaultMessage: 'Tool approval cancelled',
                })
              : intl.formatMessage({
                  id: 'pages.chat.tool.approvalHandled',
                  defaultMessage: 'Tool approval handled',
                })}
        </Typography.Text>
        <StateTag state={row.state} />
      </div>
      <div
        style={{
          marginTop: 6,
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
        }}
      >
        {calls.map((call: any, index: number) => {
          const decision = byCall.get(String(call.callId));
          return (
            <div
              key={String(call.callId || index)}
              style={{ display: 'flex', alignItems: 'center', gap: 6 }}
            >
              <ToolOutlined style={{ fontSize: 12, color: '#8c8c8c' }} />
              <Typography.Text style={{ fontSize: 12 }}>
                {(intl.locale === 'en-US'
                  ? call.displayNameEn || call.displayName
                  : call.displayName || call.displayNameEn) ||
                  call.toolName ||
                  intl.formatMessage({
                    id: 'pages.chat.tool.tool',
                    defaultMessage: 'Tool',
                  })}
                {call.action ? ` · ${call.action}` : ''}
              </Typography.Text>
              {decision ? (
                <Tag
                  color={decision === 'DENY' ? 'red' : 'blue'}
                  style={{ margin: 0 }}
                >
                  {intl.formatMessage({
                    id:
                      decision === 'DENY'
                        ? 'pages.chat.tool.deny'
                        : decision === 'ALLOW_RUN'
                          ? 'pages.chat.tool.allowRun'
                          : decision === 'ALLOW_SESSION'
                            ? 'pages.chat.tool.allowSession'
                            : 'pages.chat.tool.allowOnce',
                    defaultMessage: decision,
                  })}
                </Tag>
              ) : (
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {intl.formatMessage({
                    id: 'pages.chat.tool.waiting',
                    defaultMessage: 'Waiting',
                  })}
                </Typography.Text>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ClarificationCard({ row }: any) {
  const intl = useIntl();
  const content = row.content || {};
  const options = parseJsonArray(content.options);
  const description =
    typeof content.description === 'string' ? content.description.trim() : '';
  let selected: string[] = [];
  let text = '';
  if (content.response) {
    try {
      const parsed = JSON.parse(String(content.response));
      if (parsed && typeof parsed === 'object') {
        selected = Array.isArray(parsed.selected)
          ? parsed.selected.map(String)
          : [];
        text = typeof parsed.text === 'string' ? parsed.text : '';
      } else {
        text = String(content.response);
      }
    } catch {
      text = String(content.response);
    }
  }
  const cancelled =
    content.response === '__dismissed__' || row.state === 'CANCELLED';
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <MessageOutlined style={{ fontSize: 13, color: '#8c8c8c' }} />
        <Typography.Text strong>
          {content.title || row.title || '澄清'}
        </Typography.Text>
        <StateTag state={row.state} />
      </div>
      <div
        style={{
          marginTop: 6,
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
        }}
      >
        {description ? (
          <Typography.Paragraph
            style={{
              whiteSpace: 'pre-wrap',
              marginBottom: 0,
              color: '#595959',
              fontSize: 12,
            }}
          >
            {description}
          </Typography.Paragraph>
        ) : null}
        {row.state === 'PENDING' ? (
          <Typography.Text type="secondary">
            {intl.formatMessage({
              id: 'pages.chat.tool.waitingInDrawer',
              defaultMessage: 'Answer in the bottom panel',
            })}
          </Typography.Text>
        ) : (
          <>
            {options.length > 0 ? (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                {options.map((o: any, index: number) => {
                  const value = String(o?.value ?? o?.label ?? '');
                  const label = String(o?.label ?? value);
                  const isSelected = selected.includes(value);
                  return (
                    <Tag
                      key={`${value}-${index}`}
                      color={isSelected ? 'blue' : undefined}
                      style={{
                        margin: 0,
                        opacity: isSelected || cancelled ? 1 : 0.7,
                      }}
                    >
                      {label}
                    </Tag>
                  );
                })}
              </div>
            ) : null}
            {text ? <Typography.Text>{text}</Typography.Text> : null}
            {cancelled ? (
              <Typography.Text type="secondary">
                {intl.formatMessage({
                  id: 'pages.chat.tool.cancelled',
                  defaultMessage: 'Cancelled',
                })}
              </Typography.Text>
            ) : selected.length === 0 && !text ? (
              <Typography.Text type="secondary">
                {intl.formatMessage({
                  id: 'pages.chat.tool.answered',
                  defaultMessage: 'Answered',
                })}
              </Typography.Text>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
}

/** 子 Agent 单步：LLM 交互对齐主 Agent（Model Reason + 回复），标题不再裸展示内部枚举名。 */
function SubAgentLlmItem({ s }: any) {
  const intl = useIntl();
  const reasoningBoxRef = useRef<HTMLPreElement | null>(null);
  // 子 Agent 流式推理持续增长：贴底滚动（与主 Agent 思考容器一致）
  useEffect(() => {
    const el = reasoningBoxRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [s.reasoning]);
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <BulbOutlined style={{ fontSize: 13, color: '#8c8c8c' }} />
        <Typography.Text strong>
          {intl.formatMessage({
            id: 'pages.chat.tool.thought',
            defaultMessage: 'Model reason',
          })}
        </Typography.Text>
        {s.modelName && (
          <Tag color="blue" style={{ margin: 0, fontSize: 11 }}>
            {s.modelName}
          </Tag>
        )}
        <StateTag
          state={
            s.success === false ? 'FAILED' : s.running ? 'RUNNING' : 'COMPLETED'
          }
        />
      </div>
      {s.reasoning && (
        <Block title="Model Reason">
          <pre
            ref={reasoningBoxRef}
            style={{
              whiteSpace: 'pre-wrap',
              fontSize: 12,
              color: '#8c8c8c',
              maxHeight: 160,
              overflow: 'auto',
            }}
          >
            {s.reasoning}
          </pre>
        </Block>
      )}
      {s.reply && (
        <Block
          defaultOpen
          title={intl.formatMessage({
            id: 'pages.chat.tool.reply',
            defaultMessage: 'Reply',
          })}
        >
          <div style={{ fontSize: 12 }}>
            <XMarkdown content={s.reply} />
          </div>
          {s.running && (
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              正在生成…
            </Typography.Text>
          )}
        </Block>
      )}
    </div>
  );
}

/** 子 Agent 单步：工具调用对齐主 Agent 工具卡（🛠️ 显示名 + 状态 + 详情）。 */
function SubAgentToolItem({ s, detailOpen, onDetailToggle }: any) {
  const intl = useIntl();
  const argsJson = s.argumentsJson
    ? JSON.stringify(JSON.parse(s.argumentsJson), null, 2)
    : s.argumentsJson;
  const reason = toolCallReason({ args: s.argumentsJson });
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <ToolOutlined style={{ fontSize: 13, color: '#8c8c8c' }} />
        <Typography.Text strong>
          {(intl.locale === 'en-US'
            ? s.displayNameEn || s.displayNameCn
            : s.displayNameCn || s.displayNameEn) ||
            s.toolName ||
            intl.formatMessage({
              id: 'pages.chat.tool.tool',
              defaultMessage: 'Tool',
            })}
        </Typography.Text>
        <StateTag state={s.toolStatus || s.status} />
      </div>
      {reason && (
        <div
          title={reason}
          style={{
            marginTop: 3,
            fontSize: 11,
            lineHeight: '16px',
            color: '#8c8c8c',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {intl.locale === 'en-US' ? 'Reason: ' : '理由：'}
          {reason.slice(0, 120)}
        </div>
      )}
      {Array.isArray(s.images) && s.images.length > 0 && (
        <UserImages
          images={s.images}
          variant={s.toolName === 'builtin_5' ? 'screenshot' : 'attachment'}
        />
      )}
      {/* 受控：最新一条 Tool 详情默认展开，下一条到来时关闭之前的（单一展开） */}
      <Block
        title={intl.formatMessage({
          id: 'pages.chat.tool.details',
          defaultMessage: 'Details',
        })}
        open={detailOpen}
        onToggle={onDetailToggle}
      >
        {argsJson && (
          <pre
            style={{
              whiteSpace: 'pre-wrap',
              fontSize: 11,
              maxHeight: 140,
              overflow: 'auto',
            }}
          >
            {argsJson}
          </pre>
        )}
        {s.artifact && (
          <pre
            style={{
              whiteSpace: 'pre-wrap',
              fontSize: 11,
              maxHeight: 140,
              overflow: 'auto',
            }}
          >
            {s.artifact}
          </pre>
        )}
        {s.toolErrorMessage && (
          <div style={{ fontSize: 11, color: '#cf1322' }}>
            {s.toolErrorMessage}
          </div>
        )}
      </Block>
    </div>
  );
}

function SubAgentCard({ row, loadSubAgentSteps, subAgentLive }: any) {
  const intl = useIntl();
  const [open, setOpen] = useState(false);
  const [steps, setSteps] = useState<any[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [activeToolKey, setActiveToolKey] = useState<string | null>(null);
  const stepsBoxRef = useRef<HTMLDivElement | null>(null);
  const subId = row.refSubAgentRunId;
  const isRunning = row.state === 'RUNNING';
  const live = subId != null && isRunning ? (subAgentLive?.[subId] ?? []) : [];

  // 与 list.map 的 key 一致：历史步走主键，SSE 实时步走 publishId/序号
  const stepKey = (s: any, i: number) =>
    String(
      s?.interactionId ??
        s?.id ??
        s?.stepId ??
        (s?.type === 'tool_call'
          ? `live-tool-${s?.publishId}`
          : `live-llm-${i}`),
    );

  const list = useMemo<readonly any[]>(() => {
    const displaySteps = steps ?? (isRunning && live.length ? live : null);
    return displaySteps ?? [];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [steps, isRunning, live]);

  // 最新一条 Tool 详情默认展开；下一条到来时自动切换过去（关掉上一条）
  const latestToolKey = useMemo(() => {
    for (let i = list.length - 1; i >= 0; i--) {
      const s = list[i];
      if (s?.activityType === 'tool_call' || s?.type === 'tool_call') {
        return stepKey(s, i);
      }
    }
    return null;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list]);

  useEffect(() => {
    if (latestToolKey && latestToolKey !== activeToolKey) {
      setActiveToolKey(latestToolKey);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [latestToolKey]);

  // 最新内容自动滚到容器底部（流式推理/artifact 增长时持续贴底）
  useEffect(() => {
    if (!open) return;
    const el = stepsBoxRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [open, live, steps]);

  // 一次性权威同步：已结束子 Agent 展开 / 终态校正时静默拉取，替换 live 展示
  const stepsRef = useRef<any[] | null>(null);
  stepsRef.current = steps;
  const sync = () => {
    if (!loadSubAgentSteps || subId == null) return;
    if (stepsRef.current === null) setLoading(true);
    void loadSubAgentSteps(Number(subId))
      .then((s: any[]) => setSteps(Array.isArray(s) ? s : []))
      .catch(() => setSteps([]))
      .finally(() => setLoading(false));
  };

  const expand = () => {
    setOpen((o) => !o);
    // 运行中：纯 SSE live 渲染，不拉历史（避免与实时步骤双源叠加）；终态/已结束才一次拉取
    if (steps === null && !isRunning) sync();
  };

  // 终态（COMPLETED/FAILED/…）一次性校正：权威数据整体替换 live
  const prevStateRef = useRef(row.state);
  useEffect(() => {
    const prev = prevStateRef.current;
    prevStateRef.current = row.state;
    if (prev === 'RUNNING' && row.state !== 'RUNNING' && open) {
      sync();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [row.state]);

  return (
    <div
      style={{
        background: '#f4f7fb',
        border: '1px solid #e6edf5',
        borderRadius: 8,
        padding: '6px 10px 8px',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <RobotOutlined style={{ fontSize: 13, color: '#8c8c8c' }} />
        <Typography.Text strong>
          {stripSubAgentMarkerPrefix(row.content?.displayName || row.title) ||
            intl.formatMessage({
              id: 'pages.chat.tool.subagent',
              defaultMessage: 'Sub-agent',
            })}
        </Typography.Text>
        <StateTag state={row.state} />
        {open && isRunning && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            <LoadingOutlined spin style={{ fontSize: 11, marginRight: 4 }} />
            实时更新中
          </Typography.Text>
        )}
      </div>
      <div style={{ marginTop: 4 }}>
        <Button
          type="link"
          size="small"
          onClick={expand}
          style={{ padding: 0, fontSize: 12 }}
        >
          {open ? <DownOutlined /> : <RightOutlined />}
          {open
            ? intl.formatMessage({
                id: 'pages.chat.tool.collapse',
                defaultMessage: 'Collapse',
              })
            : intl.formatMessage({
                id: 'pages.chat.tool.expand',
                defaultMessage: 'Expand',
              })}
        </Button>
        {open && (
          <div
            ref={stepsBoxRef}
            style={{
              marginTop: 4,
              maxHeight: 260,
              overflow: 'auto',
              paddingRight: 4,
            }}
          >
            {loading && steps === null ? (
              <Typography.Text type="secondary">加载中…</Typography.Text>
            ) : list.length ? (
              list.map((s: any, i: number) => {
                const isTool =
                  s?.activityType === 'tool_call' || s?.type === 'tool_call';
                const key = stepKey(s, i);
                return (
                  <div
                    key={key}
                    style={{
                      borderTop: '1px solid #f0f0f0',
                      padding: '6px 0',
                      fontSize: 12,
                    }}
                  >
                    {isTool ? (
                      <SubAgentToolItem
                        s={s}
                        detailOpen={activeToolKey === key}
                        onDetailToggle={() =>
                          setActiveToolKey((cur) => (cur === key ? null : key))
                        }
                      />
                    ) : (
                      <SubAgentLlmItem s={s} />
                    )}
                  </div>
                );
              })
            ) : (
              <Typography.Text type="secondary">
                {isRunning
                  ? intl.formatMessage({
                      id: 'pages.chat.tool.waitingSteps',
                      defaultMessage: 'Waiting for live steps…',
                    })
                  : intl.formatMessage({
                      id: 'pages.chat.tool.noSteps',
                      defaultMessage: 'No detailed steps',
                    })}
              </Typography.Text>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function UserImages({
  images,
  variant = 'attachment',
}: {
  images: any;
  variant?: 'attachment' | 'screenshot';
}) {
  const [urls, setUrls] = useState<Record<string, string>>({});
  const currentUrlsRef = useRef<Record<string, string>>({});
  const screenshot = variant === 'screenshot';
  const imagesKey = (images || [])
    .map((im: any) => im?.fileKey || '')
    .join(',');
  useEffect(() => {
    // 按 fileKey 集合变化响应式重取：live 刷新迟到补上的 images 也能加载
    const list = (images || []).filter((im: any) => im?.fileKey);
    if (list.length === 0) return;
    let alive = true;
    const next: Record<string, string> = {};
    Promise.all(
      list.map(async (im: any) => {
        try {
          const blob = await agentApi.files.get(im.fileKey);
          if (alive) next[im.fileKey] = URL.createObjectURL(blob);
        } catch (err) {
          console.warn('[attachment] load failed', im.fileKey, err);
        }
      }),
    ).then(() => {
      if (!alive) return;
      // 新 URL 就绪后再释放旧 URL，避免短暂渲染破损图
      Object.values(currentUrlsRef.current).forEach((u) => {
        if (u) URL.revokeObjectURL(u);
      });
      currentUrlsRef.current = next;
      setUrls(next);
    });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imagesKey]);
  const list = (images || []).filter(
    (im: any) => im?.fileKey && urls[im.fileKey],
  );
  if (list.length === 0) return null;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
      <Image.PreviewGroup>
        {list.map((im: any, idx: number) => (
          <Image
            key={im.fileKey || idx}
            src={urls[im.fileKey]}
            alt="attachment"
            style={{
              maxWidth: screenshot ? 480 : 240,
              maxHeight: screenshot ? 360 : 240,
              borderRadius: 8,
              border: '1px solid #e5e5e5',
              objectFit: screenshot ? 'contain' : 'cover',
              background: screenshot ? '#f5f5f5' : undefined,
              cursor: 'pointer',
            }}
            preview={{ mask: '查看大图' }}
          />
        ))}
      </Image.PreviewGroup>
    </div>
  );
}

function subAgentMemberKey(row: any): string {
  const id = row?.refSubAgentRunId;
  return String(id ?? row?.seq ?? Math.random());
}

/** 同批次并行子 Agent 的分组键：共享 parentToolCallId（同一 delegate 调用派生）。 */
function parallelGroupKey(row: any): string | null {
  if (row?.kind !== 'subagent') return null;
  const pid = row?.content?.parentToolCallId;
  if (pid == null || pid === '') return null;
  return String(pid);
}

/**
 * 同批次并行子 Agent 的横向 Tab 容器：Tab 条原生横向滚动，
 * 面板复用 SubAgentCard（live 聚合/展开/步骤加载逻辑不变）。
 * inactive 面板默认保持挂载，切换 Tab 不丢各卡片状态。
 */
function ParallelSubAgentTabs({
  groupKey,
  members,
  loadSubAgentSteps,
  subAgentLive,
}: any) {
  const intl = useIntl();
  const keys = members.map((m: any) => subAgentMemberKey(m));
  const [activeKey, setActiveKey] = useState<string | undefined>(undefined);
  const effectiveKey =
    activeKey != null && keys.includes(activeKey) ? activeKey : keys[0];
  const anyRunning = members.some((m: any) => m.state === 'RUNNING');
  return (
    <div
      style={{
        width: '100%',
        maxWidth: 940,
        margin: '2px auto',
        fontSize: 13,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          marginBottom: 2,
        }}
      >
        <Tag style={{ margin: 0 }}>
          {intl.formatMessage({
            id: 'pages.chat.tool.subagent',
            defaultMessage: 'Sub-agent',
          })}
        </Tag>
        <Tag style={{ margin: 0, fontSize: 11, color: '#999' }}>
          ×{members.length} 并行
        </Tag>
        {anyRunning ? <RunningDot /> : null}
      </div>
      <Tabs
        size="small"
        style={{ width: '100%' }}
        tabBarStyle={{ marginBottom: 4 }}
        activeKey={effectiveKey}
        onChange={setActiveKey}
        items={members.map((m: any, i: number) => {
          const name =
            stripSubAgentMarkerPrefix(m.content?.displayName || m.title) ||
            `${intl.formatMessage({ id: 'pages.chat.tool.subagent', defaultMessage: 'Sub-agent' })} ${i + 1}`;
          return {
            key: subAgentMemberKey(m),
            label: (
              <span>
                {name}{' '}
                {m.state === 'RUNNING' ? (
                  <RunningDot />
                ) : (
                  <StateTag state={m.state} small />
                )}
              </span>
            ),
            children: (
              <SubAgentCard
                row={m}
                loadSubAgentSteps={loadSubAgentSteps}
                subAgentLive={subAgentLive}
              />
            ),
          };
        })}
      />
    </div>
  );
}

function RowCard({
  row,
  sessionId,
  isLatestAssistant,
  onRespondClarify,
  onCancelClarification,
  loadSubAgentSteps,
  subAgentLive,
}: any) {
  const { styles } = useStyles();
  const isUser = row.kind === 'user';
  // 复制目标：用户消息 / 助手回复正文（markdown 原文）
  const isAssistant = row.kind === 'assistant';
  const copyText = isUser ? row.content?.text || row.title || '' : '';

  // 压缩行：分割线 + 压缩模型的实时输出（生成中逐 token 追加，刷新后取 summary_after）
  if (row.kind === 'compaction') {
    const text = row.content?.text || '';
    const running = row.state === 'RUNNING';
    const failed = row.state === 'FAILED' || row.state === 'failed';
    return (
      <div style={{ width: '100%', maxWidth: 940, margin: '2px auto' }}>
        <Divider plain style={{ margin: '4px 0', fontSize: 12, color: '#999' }}>
          {running ? (
            <LoadingOutlined spin style={{ fontSize: 12 }} />
          ) : failed ? (
            <CloseOutlined style={{ fontSize: 12, color: '#ff4d4f' }} />
          ) : (
            <SyncOutlined style={{ fontSize: 12, color: '#8c8c8c' }} />
          )}{' '}
          {row.title || '上下文压缩'}
        </Divider>
        {text ? (
          <div
            style={{
              fontSize: 12,
              color: '#8c8c8c',
              borderRadius: 6,
              padding: '6px 10px',
              maxHeight: 260,
              overflow: 'auto',
            }}
          >
            <XMarkdown content={text} />
          </div>
        ) : running ? (
          <div style={{ fontSize: 12, color: '#bfbfbf', paddingLeft: 4 }}>
            （压缩中…）
          </div>
        ) : null}
      </div>
    );
  }

  // 状态行：横线 + 状态 + 耗时 + LLM 模型名（不再渲染卡片）
  if (row.kind === 'run_status') {
    const duration = formatDuration(row.content?.durationMs);
    const usage = row.content?.usage;
    const isRunFailed = row.state === 'FAILED' || row.state === 'failed';
    // 后端失败文案「❌ 执行失败」的红叉太粗，前端剥掉改细线红叉（与行文字同字号）。
    const text = (row.content?.text || row.title || '')
      .replace(/^❌\s*/, '')
      .replace(/^⏹️\s*/, '')
      .replace(/^⏸️\s*/, '');
    const parts = [
      text,
      duration,
      row.content?.modelName,
      usage && usage.totalTokens
        ? `Tokens ${formatTokens(usage.totalTokens)}`
        : null,
    ].filter((p): p is string => !!p);
    return (
      <div style={{ width: '100%', maxWidth: 940, margin: '2px auto' }}>
        <Divider plain style={{ margin: '4px 0', fontSize: 12, color: '#999' }}>
          {row.state === 'RUNNING' ? (
            <LoadingOutlined spin style={{ fontSize: 12 }} />
          ) : isRunFailed ? (
            <CloseCircleOutlined style={{ fontSize: 12, color: '#ff4d4f' }} />
          ) : row.state === 'AWAITING_USER' ? (
            <PauseCircleOutlined style={{ fontSize: 12 }} />
          ) : row.state === 'CANCELLED' ? (
            <StopOutlined style={{ fontSize: 12 }} />
          ) : (
            <CheckCircleOutlined style={{ fontSize: 12 }} />
          )}{' '}
          {parts.join(' · ')}
        </Divider>
      </div>
    );
  }

  const body = (() => {
    switch (row.kind) {
      case 'user':
        return (
          <>
            {Array.isArray(row.content?.images) && (
              <UserImages images={row.content.images} />
            )}
            <Typography.Text>{row.content?.text || row.title}</Typography.Text>
          </>
        );
      case 'assistant':
        return (
          <AssistantCard
            row={row}
            sessionId={sessionId}
            isLatest={isLatestAssistant}
          />
        );
      case 'tool':
        return <ToolCard row={row} />;
      case 'tool_approval':
        return <ToolApprovalCard row={row} />;
      case 'clarification':
        return (
          <ClarificationCard
            row={row}
            onRespondClarify={onRespondClarify}
            onCancelClarification={onCancelClarification}
          />
        );
      case 'subagent':
        return (
          <SubAgentCard
            row={row}
            loadSubAgentSteps={loadSubAgentSteps}
            subAgentLive={subAgentLive}
          />
        );
      case 'run_status':
      case 'error':
        return (
          <Typography.Text type="secondary">
            {row.content?.text || row.title}
          </Typography.Text>
        );
      default:
        return <Typography.Text>{row.title}</Typography.Text>;
    }
  })();

  return (
    <div
      className={isUser || isAssistant ? styles.copyHover : undefined}
      style={{
        width: '100%',
        maxWidth: 940,
        margin: '2px auto',
        fontSize: 13,
        display: 'flex',
        flexDirection: 'column',
        alignItems: isUser ? 'flex-end' : 'stretch',
      }}
    >
      {isUser ? (
        <div
          style={{
            background: '#e6f7ea',
            borderRadius: 8,
            padding: '8px 12px',
            maxWidth: '85%',
            whiteSpace: 'pre-wrap',
          }}
        >
          {body}
        </div>
      ) : (
        <>
          {row.kind === 'subagent' ? (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                marginBottom: 2,
              }}
            >
              <Tag style={{ margin: 0 }}>
                {KIND_LABEL[row.kind] || row.kind}
              </Tag>
              <StateTag state={row.state} />
            </div>
          ) : row.kind === 'assistant' && row.state === 'RUNNING' ? (
            <div style={{ marginBottom: 2 }}>
              <RunningDot />
            </div>
          ) : (
            <div style={{ marginBottom: 2 }} />
          )}
          {body}
        </>
      )}
      {copyText && (
        <div
          className="copy-btn"
          style={{
            alignSelf: isUser ? 'flex-end' : 'flex-start',
            marginTop: 2,
          }}
        >
          <CopyButton text={copyText} />
        </div>
      )}
    </div>
  );
}

export default function TimelineList({
  rows,
  hasMore,
  sessionId,
  onLoadOlder,
  onRespondClarify,
  onCancelClarification,
  loadSubAgentSteps,
  subAgentLive,
  preparingTool,
}: TimelineListProps) {
  const intl = useIntl();
  // 同批次并行子 Agent（共享 parentToolCallId 且 ≥2 个）合并为横向 Tab；
  // 单个子 Agent / 缺分组键的过渡态仍走原 RowCard，保证行为不变。
  // 同一 subId 的占位行与权威行可能短暂共存，去重时优先保留权威行（seq>=0）。
  const latestAssistantSeq = useMemo(() => {
    for (let i = rows.length - 1; i >= 0; i -= 1) {
      if (rows[i]?.kind === 'assistant') return rows[i].seq;
    }
    return null;
  }, [rows]);

  const items = useMemo(() => {
    const counts = new Map<string, number>();
    for (const r of rows) {
      const k = parallelGroupKey(r);
      if (k) counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    const consumed = new Set<string>();
    const out: Array<
      { type: 'row'; row: any } | { type: 'group'; key: string; members: any[] }
    > = [];
    for (const r of rows) {
      if (isInternalToolRow(r)) {
        continue;
      }
      const k = parallelGroupKey(r);
      if (k && (counts.get(k) ?? 0) >= 2 && !consumed.has(k)) {
        consumed.add(k);
        const bySub = new Map<string, any>();
        for (const m of rows) {
          if (parallelGroupKey(m) !== k) continue;
          const mk = subAgentMemberKey(m);
          const prev = bySub.get(mk);
          if (!prev || (prev.seq ?? 0) < 0) bySub.set(mk, m);
        }
        out.push({
          type: 'group',
          key: `parallel-${k}`,
          members: [...bySub.values()],
        });
      } else if (k && consumed.has(k)) {
      } else {
        out.push({ type: 'row', row: r });
      }
    }
    return out;
  }, [rows]);

  return (
    <>
      {hasMore && (
        <div style={{ textAlign: 'center' }}>
          <Button type="link" size="small" onClick={onLoadOlder}>
            加载更早
          </Button>
        </div>
      )}
      {items.map((it) =>
        it.type === 'row' ? (
          <RowCard
            key={`${it.row.seq}-${it.row.kind}`}
            row={it.row}
            sessionId={sessionId}
            isLatestAssistant={
              it.row.kind === 'assistant' && it.row.seq === latestAssistantSeq
            }
            onRespondClarify={onRespondClarify}
            onCancelClarification={onCancelClarification}
            loadSubAgentSteps={loadSubAgentSteps}
            subAgentLive={subAgentLive}
          />
        ) : (
          <ParallelSubAgentTabs
            key={it.key}
            groupKey={it.key}
            members={it.members}
            loadSubAgentSteps={loadSubAgentSteps}
            subAgentLive={subAgentLive}
          />
        ),
      )}
      {preparingTool ? (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            fontSize: 12,
            color: '#8c8c8c',
            padding: '4px 0',
          }}
        >
          <LoadingOutlined spin style={{ fontSize: 12 }} />
          <span>
            {preparingTool.name || '工具'} ·{' '}
            {intl.formatMessage({
              id: 'pages.chat.tool.preparingArguments',
              defaultMessage: 'Generating call arguments…',
            })}
          </span>
        </div>
      ) : null}
    </>
  );
}

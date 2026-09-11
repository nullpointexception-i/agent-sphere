import {
  ArrowRightOutlined,
  CheckOutlined,
  CloseOutlined,
  CopyOutlined,
  DownOutlined,
  RightOutlined,
} from '@ant-design/icons';
import XMarkdown from '@ant-design/x-markdown';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import {
  App,
  Button,
  Checkbox,
  Divider,
  Image,
  Input,
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
  docSummary,
  extractTodos,
  genericSummary,
  isBrowserTool,
  type TodoItem,
  type ToolFamily,
  todoProgress,
  todoTotal,
  toolFamily,
} from './toolRenderers';

interface TimelineListProps {
  rows: any[];
  hasMore: boolean;
  onLoadOlder: () => void;
  onRespondClarify?: (c: any, resp: string) => void;
  onCancelClarification?: (row: any) => void;
  loadSubAgentSteps?: (subId: number) => Promise<any[]>;
  subAgentLive?: SubAgentLiveMap;
}

const KIND_LABEL: Record<string, string> = {
  user: '用户',
  assistant: '助手',
  tool: '工具',
  clarification: '澄清',
  subagent: '子 Agent',
  run_status: '状态',
  error: '错误',
};

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

function AssistantCard({ row }: any) {
  const content = row.content || {};
  const thinking = content.thinking || '';
  const reply = content.reply || '';
  const [thinkingOpen, setThinkingOpen] = useState(true);
  const [autoCollapsed, setAutoCollapsed] = useState(false);
  const thinkingBoxRef = useRef<HTMLPreElement | null>(null);
  // 流式思考持续增长：展开且内容变化时自动滚底（与子 Agent 步骤容器行为一致）
  useEffect(() => {
    if (!thinkingOpen) return;
    const el = thinkingBoxRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [thinking, thinkingOpen]);
  useEffect(() => {
    if (!autoCollapsed && reply.trim().length > 0) {
      setAutoCollapsed(true);
      setThinkingOpen(false);
    }
  }, [reply, autoCollapsed]);
  return (
    <div>
      <Block
        title={`Model Reason${thinking ? '' : '（无）'}`}
        open={thinkingOpen}
        onToggle={() => setThinkingOpen((o) => !o)}
      >
        <pre
          ref={thinkingBoxRef}
          style={{
            whiteSpace: 'pre-wrap',
            fontSize: 12,
            color: '#8c8c8c',
            maxHeight: 240,
            overflow: 'auto',
          }}
        >
          {thinking || '（无推理内容）'}
        </pre>
      </Block>
      <Block defaultOpen title={'回复'}>
        <div style={{ fontSize: 13 }}>
          {reply ? (
            <XMarkdown content={reply} />
          ) : (
            <Typography.Text type="secondary">（流式中…）</Typography.Text>
          )}
        </div>
      </Block>
      <UsageChip usage={content.usage} />
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

/** 原文 JSON 折叠块（默认关闭，不丢细节）。 */
function JsonBlock({ label, json }: { label: string; json?: string }) {
  if (!json) return null;
  let pretty = json;
  try {
    pretty = JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    /* 保持原文 */
  }
  return (
    <Block title={label} faint>
      <pre
        style={{
          whiteSpace: 'pre-wrap',
          fontSize: 10.5,
          color: '#999',
          maxHeight: 140,
          overflow: 'auto',
        }}
      >
        {pretty}
      </pre>
    </Block>
  );
}

function TodoCard({ content }: { content: any }) {
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
        border: '1px solid #d9d9d9',
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
          待办进度
        </Typography.Text>
        {summaryChip}
      </div>
      {todos.length === 0 ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          无待办项
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
            </div>
          );
        })
      )}
      <JsonBlock label="查看原始待办" json={content.args || content.artifact} />
    </div>
  );
}

function DocCard({ content }: { content: any }) {
  const summary = docSummary(content);
  const artifact = content.artifact;
  let artifactObj: any = null;
  try {
    artifactObj = artifact ? JSON.parse(artifact) : null;
  } catch {
    artifactObj = null;
  }
  return (
    <div style={{ marginTop: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 12, opacity: 0.7 }}>📄</span>
        <Typography.Text style={{ fontSize: 12, color: '#8c8c8c' }}>
          文档操作
        </Typography.Text>
        <ArrowRightOutlined style={{ fontSize: 11, color: '#a0a0a0' }} />
        <Typography.Text strong style={{ fontSize: 12 }}>
          {summary}
        </Typography.Text>
      </div>
      {artifactObj?.documentId && (
        <div style={{ marginTop: 4 }}>
          <Typography.Text
            type="secondary"
            style={{ fontSize: 11, lineHeight: '16px' }}
          >
            {(artifactObj.preview || artifactObj.title || '') && (
              <>
                {artifactObj.title || '文档'}
                {artifactObj.preview
                  ? `：${String(artifactObj.preview).slice(0, 60)}…`
                  : ''}
              </>
            )}
          </Typography.Text>
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
  const summary = genericSummary(content);
  const displayName =
    content.displayName ||
    (family === 'todo' ? '待办写入' : content.toolName) ||
    '工具';
  return (
    <div style={{ marginTop: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 12, opacity: 0.7 }}>🛠️</span>
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
      {!hideJson && <JsonBlock label="查看原始参数" json={content.args} />}
      {!hideJson && <JsonBlock label="查看原始结果" json={content.artifact} />}
    </div>
  );
}

function ToolCard({ row }: any) {
  const content = row.content || {};
  const family = toolFamily(content, content.displayName);
  const name = content.displayName || row.title || 'tool';
  // 「委派子 Agent」工具调用在下方已由 SubAgentCard 完整展示，顶部标题行冗余，隐藏。
  const isDelegate = /委派|子 ?agent/i.test(name);
  // 浏览器工具：后端把超时/失败编码进 artifact（row.state 恒为 SUCCEEDED），此处按 artifact 解析真实状态。
  const isBrowser = isBrowserTool(content, content.displayName);
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
          <span style={{ fontSize: 12, opacity: 0.7 }}>🛠️</span>
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
          hideJson={isDelegate || isBrowser}
          stateLabel={stateLabel}
        />
      )}
    </div>
  );
}

function ClarificationCard({
  row,
  onRespondClarify,
  onCancelClarification,
}: any) {
  const content = row.content || {};
  const [text, setText] = useState('');
  const options = (() => {
    try {
      const parsed = JSON.parse(content.options || '[]');
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  })();
  const respond = (value: string) => {
    const v = String(value ?? '').trim();
    if (!v) return;
    onRespondClarify?.(
      { runId: row.runId, clarificationId: content.clarificationId },
      v,
    );
    setText('');
  };
  const cancel = () =>
    onCancelClarification?.({
      runId: row.runId,
      clarificationId: content.clarificationId,
    });
  return (
    <div>
      <Typography.Text strong>
        {content.title || row.title || '澄清'}
      </Typography.Text>
      <StateTag state={row.state} />
      <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        {row.state === 'PENDING' ? (
          <>
            {options.length === 0 && (
              <Button size="small" onClick={() => respond('confirmed')}>
                确认
              </Button>
            )}
            {options.map((o: any) => (
              <Button
                key={String(o.value ?? o.label ?? o.title)}
                size="small"
                onClick={() => respond(o.value ?? o.label)}
              >
                {o.label || o.value}
              </Button>
            ))}
            <Input.TextArea
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="输入澄清内容…"
              autoSize={{ minRows: 2, maxRows: 4 }}
              style={{ marginTop: 6 }}
            />
            <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
              <Button
                type="primary"
                size="small"
                disabled={!text.trim()}
                onClick={() => respond(text)}
              >
                提交
              </Button>
              {onCancelClarification && (
                <Button size="small" onClick={cancel}>
                  取消
                </Button>
              )}
            </div>
          </>
        ) : (
          <>
            <Typography.Text type="secondary">已应答</Typography.Text>
            {content.response && (
              <Typography.Text>{String(content.response)}</Typography.Text>
            )}
          </>
        )}
      </div>
    </div>
  );
}

/** 子 Agent 单步：LLM 交互对齐主 Agent（Model Reason + 回复），标题不再裸展示内部枚举名。 */
function SubAgentLlmItem({ s }: any) {
  const reasoningBoxRef = useRef<HTMLPreElement | null>(null);
  // 子 Agent 流式推理持续增长：贴底滚动（与主 Agent 思考容器一致）
  useEffect(() => {
    const el = reasoningBoxRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [s.reasoning]);
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span>🧠</span>
        <Typography.Text strong>模型推理</Typography.Text>
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
        <Block defaultOpen title="回复">
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
  const argsJson = s.argumentsJson
    ? JSON.stringify(JSON.parse(s.argumentsJson), null, 2)
    : s.argumentsJson;
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span>🛠️</span>
        <Typography.Text strong>
          {s.displayNameCn || s.displayNameEn || s.toolName || '工具'}
        </Typography.Text>
        <StateTag state={s.toolStatus || s.status} />
      </div>
      {Array.isArray(s.images) && s.images.length > 0 && (
        <UserImages images={s.images} />
      )}
      {/* 受控：最新一条 Tool 详情默认展开，下一条到来时关闭之前的（单一展开） */}
      <Block title="详情" open={detailOpen} onToggle={onDetailToggle}>
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
        <span>⚙️</span>
        <Typography.Text strong>
          {stripSubAgentMarkerPrefix(row.content?.displayName || row.title) ||
            '子 Agent'}
        </Typography.Text>
        <StateTag state={row.state} />
        {open && isRunning && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            ⟳ 实时更新中
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
          {open ? '收起步骤' : '展开步骤'}
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
                {isRunning ? '（等待实时步骤…）' : '（无详细步骤）'}
              </Typography.Text>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function UserImages({ images }: any) {
  const [urls, setUrls] = useState<Record<string, string>>({});
  const currentUrlsRef = useRef<Record<string, string>>({});
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
              maxWidth: 240,
              maxHeight: 240,
              borderRadius: 8,
              border: '1px solid #e5e5e5',
              objectFit: 'cover',
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
        <Tag style={{ margin: 0 }}>子 Agent</Tag>
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
            `子 Agent ${i + 1}`;
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
  onRespondClarify,
  onCancelClarification,
  loadSubAgentSteps,
  subAgentLive,
}: any) {
  const { styles } = useStyles();
  const isUser = row.kind === 'user';
  // 复制目标：用户消息 / 助手回复正文（markdown 原文）
  const isAssistant = row.kind === 'assistant';
  const copyText = isUser
    ? row.content?.text || row.title || ''
    : isAssistant
      ? row.content?.reply || ''
      : '';

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
            '⟳'
          ) : isRunFailed ? (
            <CloseOutlined style={{ fontSize: 12, color: '#ff4d4f' }} />
          ) : (
            '•'
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
        return <AssistantCard row={row} />;
      case 'tool':
        return <ToolCard row={row} />;
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
  onLoadOlder,
  onRespondClarify,
  onCancelClarification,
  loadSubAgentSteps,
  subAgentLive,
}: TimelineListProps) {
  // 同批次并行子 Agent（共享 parentToolCallId 且 ≥2 个）合并为横向 Tab；
  // 单个子 Agent / 缺分组键的过渡态仍走原 RowCard，保证行为不变。
  // 同一 subId 的占位行与权威行可能短暂共存，去重时优先保留权威行（seq>=0）。
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
    </>
  );
}

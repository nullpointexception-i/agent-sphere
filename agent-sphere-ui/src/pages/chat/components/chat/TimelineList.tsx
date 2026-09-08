import {
  CheckOutlined,
  CopyOutlined,
  DownOutlined,
  RightOutlined,
} from '@ant-design/icons';
import XMarkdown from '@ant-design/x-markdown';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { App, Button, Divider, Image, Input, Tag, Typography } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { useStyles } from '../../style';
import type { SubAgentLiveMap } from './subAgentTypes';

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
      : state === 'FAILED' || state === 'failed' || state === 'CANCELLED'
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
    </div>
  );
}

function ToolCard({ row }: any) {
  const content = row.content || {};
  const args = content.args
    ? JSON.stringify(JSON.parse(content.args), null, 2)
    : content.args;
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 12, opacity: 0.7 }}>🛠️</span>
        <Typography.Text strong style={{ fontSize: 12, color: '#8c8c8c' }}>
          {content.displayName || row.title || 'tool'}
        </Typography.Text>
        <StateTag state={row.state} small />
      </div>
      {Array.isArray(content.images) && content.images.length > 0 && (
        <UserImages images={content.images} />
      )}
      <Block title={'详情'} faint>
        {args && (
          <pre
            style={{
              whiteSpace: 'pre-wrap',
              fontSize: 10.5,
              color: '#999',
              maxHeight: 140,
              overflow: 'auto',
            }}
          >
            {args}
          </pre>
        )}
        {content.artifact && (
          <pre
            style={{
              whiteSpace: 'pre-wrap',
              fontSize: 10.5,
              color: '#999',
              maxHeight: 140,
              overflow: 'auto',
            }}
          >
            {content.artifact}
          </pre>
        )}
      </Block>
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
          {row.content?.displayName || row.title || '子 Agent'}
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
    const parts = [
      row.content?.text || row.title,
      duration,
      row.content?.modelName,
    ].filter((p): p is string => !!p);
    return (
      <div style={{ width: '100%', maxWidth: 940, margin: '2px auto' }}>
        <Divider plain style={{ margin: '4px 0', fontSize: 12, color: '#999' }}>
          {row.state === 'RUNNING' ? '⟳' : '•'} {parts.join(' · ')}
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
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              marginBottom: 2,
            }}
          >
            <Tag
              style={
                row.kind === 'tool'
                  ? { margin: 0, fontSize: 11, color: '#999' }
                  : { margin: 0 }
              }
            >
              {KIND_LABEL[row.kind] || row.kind}
            </Tag>
            {/* 助手 RUNNING → 晃动小球；终态 → 状态位消失（不显示文字标签） */}
            {row.kind === 'assistant' ? (
              row.state === 'RUNNING' ? (
                <RunningDot />
              ) : null
            ) : (
              <StateTag state={row.state} />
            )}
          </div>
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
  return (
    <>
      {hasMore && (
        <div style={{ textAlign: 'center' }}>
          <Button type="link" size="small" onClick={onLoadOlder}>
            加载更早
          </Button>
        </div>
      )}
      {rows.map((row) => (
        <RowCard
          key={`${row.seq}-${row.kind}`}
          row={row}
          onRespondClarify={onRespondClarify}
          onCancelClarification={onCancelClarification}
          loadSubAgentSteps={loadSubAgentSteps}
          subAgentLive={subAgentLive}
        />
      ))}
    </>
  );
}

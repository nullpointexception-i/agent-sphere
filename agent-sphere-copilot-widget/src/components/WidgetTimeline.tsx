import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Markdown } from '../markdown';
import {
  ApprovalIcon,
  BrainIcon,
  CheckIcon,
  ClockIcon,
  CloseIcon,
  CopyIcon,
  MessageIcon,
  PauseIcon,
  RefreshIcon,
  ToolIcon,
} from '../icons';
import { stripSubAgentMarkerPrefix } from '../subAgentMarker';
import {
  browserResult,
  cliView,
  isBrowserTool,
  progressView,
  readToolResultView,
  searchResultView,
  skillView,
  textContainsView,
  toolCallReason,
  toolFamily,
  toolResultText,
  webPageView,
} from '../toolRenderers';
import {
  ContentPreview,
  DocCard,
  formatToolDuration,
  GenericCard,
  RawData,
  TodoCard,
} from './ToolCard';
import UsageChip from './Usage';
import type { SubAgentTimelineItemVO, TimelineRow } from '../types';
import type { SubAgentLiveMap } from '../useTimelineStream';

export interface WidgetTimelineProps {
  rows: TimelineRow[];
  hasMore: boolean;
  loadingOlder: boolean;
  onLoadOlder: () => void;
  /** 乐观插入、等待后端权威行替换的用户消息（渲染在末尾）。 */
  pendingUserRows?: TimelineRow[];
  subAgentLiveMap: SubAgentLiveMap;
  loadSubAgentSteps: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  /** 按 fileKey 拉附件字节转 objectURL（用户消息图片回显）。 */
  loadFile: (fileKey: string) => Promise<string>;
  onRespondClarify: (runId: number, clarificationId: string, response: string) => void;
  /** 取消当前会话待处理澄清（停止 run）。 */
  onCancelClarify?: () => void;
  preparingTool?: { publishId?: string; name?: string } | null;
}

// ---------------------------------------------------------------- utils

// 内部协议工具：finish 的答案已作为助手正文展示，ask_clarification 由澄清行展示。
const FINISH_TOOL_NAME = 'builtin_12';
const ASK_CLARIFICATION_TOOL_NAME = 'builtin_7';
/** 超过该耗时视为慢工具（红色 + ⚠）。 */
const SLOW_TOOL_MS = 5000;

function isInternalToolRow(row: TimelineRow): boolean {
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

function formatDuration(ms?: number | null): string {
  if (ms == null || ms < 0 || !Number.isFinite(ms)) return '';
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `${sec}秒`;
  return `${Math.floor(sec / 60)}分${sec % 60}秒`;
}

const STATE_COLORS: Record<string, string> = {
  RUNNING: '#1677ff',
  PENDING: '#faad14',
  in_progress: '#1677ff',
  pending: '#faad14',
  COMPLETED: '#52c41a',
  SUCCEEDED: '#52c41a',
  succeeded: '#52c41a',
  FAILED: '#ff4d4f',
  failed: '#ff4d4f',
  CANCELLED: '#8c8c8c',
  ANSWERED: '#52c41a',
  ACTIVE: '#52c41a',
};

function stateColor(state?: string | number | null): string {
  return (state != null && STATE_COLORS[String(state)]) || '#8c8c8c';
}

function StateTag({ state }: { state?: string | number | null }) {
  const color = stateColor(state);
  return (
    <span
      className="aw-tl-state"
      style={{ color, background: `${color}1a` }}
    >
      {state == null ? 'RUNNING' : String(state)}
    </span>
  );
}

function prettyJson(raw?: string | null): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function Md({ text }: { text: string }) {
  return <Markdown text={text} />;
}

function CopyBtn({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const onCopy = async () => {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // 剪贴板不可用时静默
    }
  };
  return (
    <button
      type="button"
      className="aw-copy-btn"
      title={copied ? '已复制' : '复制'}
      aria-label={copied ? '已复制' : '复制'}
      onClick={onCopy}
    >
      {copied ? <CheckIcon size={13} /> : <CopyIcon size={13} />}
    </button>
  );
}

// ---------------------------------------------------------------- rows

function AssistantRow({ row }: { row: TimelineRow }) {
  const c = row.content || {};
  const thinking = c.thinking || '';
  const reply = c.reply || '';
  const [thinkingOpen, setThinkingOpen] = useState(
    !row.state || row.state === 'RUNNING',
  );
  const [autoCollapsed, setAutoCollapsed] = useState(false);
  const thinkBoxRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const shouldCollapse =
      reply.trim().length > 0 ||
      (!!row.state && row.state !== 'RUNNING');
    if (!autoCollapsed && shouldCollapse) {
      setAutoCollapsed(true);
      setThinkingOpen(false);
    }
  }, [reply, row.state, autoCollapsed]);
  // 思考流式增长时贴底滚动
  useEffect(() => {
    const el = thinkBoxRef.current;
    if (thinkingOpen && el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [thinking, thinkingOpen]);
  return (
    <div>
      <div>
        <button
          type="button"
          className="aw-tl-thinking-toggle"
          onClick={() => setThinkingOpen((o) => !o)}
        >
          {thinkingOpen ? '▾' : '▸'} Model Reason{thinking ? '' : '（无）'}
        </button>
      </div>
      {thinkingOpen && (
        <div ref={thinkBoxRef} className="aw-tl-thinking">
          {thinking || '（无推理内容）'}
        </div>
      )}
      <div className="aw-tl-reply">
        {reply ? (
          <Md text={reply} />
        ) : (
          <span style={{ color: '#9ca3af' }}>（流式中…）</span>
        )}
        <CopyBtn text={reply} />
        <UsageChip usage={c.usage ?? undefined} />
      </div>
    </div>
  );
}

function ToolRowBody({
  row,
  loadFile,
}: {
  row: TimelineRow;
  loadFile: (fileKey: string) => Promise<string>;
}) {
  const c = row.content || {};
  if (/^mcp_\d+_\d+$/.test(String(c.toolName || ''))) {
    return <McpToolRow content={c} state={row.state} />;
  }
  if (c.toolName === 'builtin_10' || c.displayName === '等待'
      || c.displayName === 'Wait') {
    return <WaitRow content={c} state={row.state} />;
  }
  if (c.toolName === 'builtin_13' || c.displayName === '当前时间'
      || c.displayName === 'Current Time') {
    return <CurrentTimeRow content={c} state={row.state} />;
  }
  if (c.toolName === 'builtin_11' || c.displayName === '文本包含判断'
      || c.displayName === 'Text Contains') {
    return <TextContainsRow content={c} state={row.state} />;
  }
  if (c.toolName === 'builtin_16' || c.displayName === '报告进度'
      || c.displayName === 'Progress') {
    return <ProgressRow content={c} state={row.state} />;
  }
  if (c.toolName === 'builtin_4' || c.displayName === '网页读取'
      || c.displayName === 'Web Read') {
    return <WebPageRow content={c} state={row.state} kind="read" />;
  }
  if (c.toolName === 'builtin_2' || c.displayName === '网页获取'
      || c.displayName === 'Web Fetch') {
    return <WebPageRow content={c} state={row.state} kind="fetch" />;
  }
  if (c.toolName === 'builtin_8' || c.displayName === '技能库'
      || c.displayName === 'Skill Library') {
    return <SkillRow content={c} state={row.state} kind="library" />;
  }
  if (c.toolName === 'builtin_9' || c.displayName === '技能管理'
      || c.displayName === 'Skill Manage') {
    return <SkillRow content={c} state={row.state} kind="manage" />;
  }
  if (c.toolName === 'builtin_14'
      || /回读工具结果|read tool result/i.test(c.displayName || '')) {
    return <ReadToolResultRow content={c} state={row.state} />;
  }
  if (/^cli_\d+$/.test(String(c.toolName || ''))) {
    return <CliRow content={c} state={row.state} />;
  }
  if (c.toolName === 'builtin_5' || isBrowserTool(c, c.displayName)) {
    return <BrowserRow content={c} state={row.state} loadFile={loadFile} />;
  }
  const family = toolFamily(c, c.displayName);
  const name = c.displayName || row.title || 'tool';
  const isBrowser = isBrowserTool(c, c.displayName);
  const brow = browserResult(c, c.displayName);
  const stateLabel = brow.failed
    ? brow.errorCategory === 'timeout'
      ? 'timeout'
      : 'failed'
    : undefined;
  // doc 卡自带 📄 标题行，避免与头部重复
  const hideHead = family === 'doc';
  return (
    <div>
      {!hideHead && (
        <div className="aw-tl-tool-head">
          <ToolIcon />
          <TypographyStrong>{name}</TypographyStrong>
          {family !== 'todo' &&
            (stateLabel ? (
              <span className="aw-tool-failed"><CloseIcon size={11} /> {stateLabel}</span>
            ) : (
              <StateTag state={row.state} />
            ))}
        </div>
      )}
      {Array.isArray(c.images) && c.images.length > 0 && (
        <UserImages images={c.images} loadFile={loadFile} />
      )}
      {family === 'todo' ? (
        <TodoCard content={c} />
      ) : family === 'doc' ? (
        <DocCard content={c} />
      ) : (
        <GenericCard
          content={{ ...c, displayName: name }}
          hideJson={isBrowser}
        />
      )}
    </div>
  );
}

function ToolRow(props: {
  row: TimelineRow;
  loadFile: (fileKey: string) => Promise<string>;
}) {
  const raw = props.row?.content?.meta?.durationMs;
  const ms = typeof raw === 'number' ? raw : undefined;
  const durationText = formatToolDuration(ms);
  const slow = typeof ms === 'number' && ms >= SLOW_TOOL_MS;
  const reason = toolCallReason(props.row?.content || {});
  return (
    <div>
      <ToolRowBody {...props} />
      {reason && (
        <div
          title={reason}
          style={{
            marginTop: 4,
            fontSize: 11,
            lineHeight: '16px',
            color: '#8c8c8c',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          理由：{reason.slice(0, 120)}
        </div>
      )}
      {durationText && (
        <div
          style={{
            marginTop: 4,
            fontSize: 10.5,
            color: slow ? '#cf1322' : '#a0a0a0',
            userSelect: 'none',
          }}
        >
          {slow ? `⚠ ${durationText}` : durationText}
        </div>
      )}
    </div>
  );
}

function TypographyStrong({ children }: { children: ReactNode }) {
  return (
    <strong style={{ fontSize: 12, color: '#8c8c8c' }}>{children}</strong>
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

function McpToolRow({ content, state }: { content: any; state?: string | null }) {
  const server = typeof content.meta?.server === 'string' ? content.meta.server : '';
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
    resultStatus = artifact?.status || artifact?.data?.status || artifact?.data?.action || artifact?.action || '';
  } catch {
    if (typeof content.artifact === 'string' && content.artifact.length <= 40) {
      resultStatus = content.artifact;
    }
  }
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <ToolIcon />
        <TypographyStrong>{content.displayName || content.toolName || '工具'}</TypographyStrong>
        {server ? <span style={{ fontSize: 11, color: '#9ca3af' }}>· {server}</span> : null}
        {action ? <span style={{ fontSize: 12, color: '#6b7280' }}>· {action}</span> : null}
        {resultStatus ? (
          <span style={{ fontSize: 11, color: '#1677ff', border: '1px solid #91caff', borderRadius: 4, padding: '0 4px' }}>
            {String(resultStatus)}
          </span>
        ) : null}
        <StateTag state={state} />
      </div>
      {results.length > 0 && (
        <div style={{ marginTop: 5, display: 'flex', flexDirection: 'column', gap: 4 }}>
          {results.map((result) => (
            <div key={result.url || result.title} style={{ fontSize: 11, lineHeight: '16px', minWidth: 0 }}>
              <div
                title={result.url || result.title}
                style={{ color: '#1677ff', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
              >
                {result.title}
              </div>
              <div
                title={result.summary}
                style={{ color: '#9ca3af', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
              >
                {[result.host, result.summary].filter(Boolean).join(' · ')}
              </div>
            </div>
          ))}
        </div>
      )}
      <div>
        <ContentPreview
          title={content.displayName || content.toolName || '工具'}
          content={toolResultText(content)}
        />
      </div>
      <RawData content={content} />
    </div>
  );
}

const TEXT_MATCH_MODE_LABEL: Record<string, string> = {
  EXACT: '精确匹配',
  IGNORE_CASE: '忽略大小写',
  TRIMMED: '去首尾空白',
  IGNORE_CASE_TRIMMED: '忽略大小写 + 去空白',
};

function prettyJsonSafe(raw?: string) {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function TextContainsRow({
  content,
  state,
}: {
  content: any;
  state?: string | null;
}) {
  const view = textContainsView(content);
  const failed = view.error != null;
  const matched = !failed && view.hit === true;
  const unknown = !failed && view.hit === null;
  const background = failed ? '#fff1f0' : matched ? '#f6ffed' : unknown ? '#f0f5ff' : '#fafafa';
  const showState = failed || String(state ?? '').toLowerCase() !== 'succeeded';
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <ToolIcon />
        <TypographyStrong>文本包含判断</TypographyStrong>
        <span
          style={{
            fontSize: 10,
            lineHeight: '16px',
            padding: '0 5px',
            borderRadius: 4,
            color: failed ? '#cf1322' : matched ? '#389e0d' : unknown ? '#1677ff' : '#8c8c8c',
            background: failed ? '#ffccc7' : matched ? '#d9f7be' : unknown ? '#d6e4ff' : '#f0f0f0',
          }}
        >
          {failed ? '失败' : matched ? '命中' : unknown ? '无结果' : '未命中'}
        </span>
        <span style={{ color: '#9ca3af', fontSize: 11 }}>
          {TEXT_MATCH_MODE_LABEL[view.mode] || view.mode}
        </span>
        {showState && <StateTag state={state} />}
      </div>
      {failed && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#cf1322' }}>
          {view.error}
        </div>
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
              <span style={{ flexShrink: 0 }}>查找</span>
              <span
                title={view.needle}
                style={{
                  fontFamily: 'monospace',
                  maxWidth: 220,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  color: '#595959',
                }}
              >
                {view.needle}
              </span>
            </>
          )}
          {view.haystackChars != null && (
            <span style={{ flexShrink: 0 }}>
              · 目标 {view.haystackChars.toLocaleString()} 字符
            </span>
          )}
        </div>
      )}
      <details style={{ marginTop: 5 }}>
        <summary
          style={{
            cursor: 'pointer',
            fontSize: 10.5,
            color:
              typeof content?.meta?.durationMs === 'number' &&
              content.meta.durationMs >= 5000
                ? '#cf1322'
                : '#a0a0a0',
            userSelect: 'none',
          }}
        >
          查看原始数据
          {formatToolDuration(content?.meta?.durationMs)
            ? ` · ${content.meta.durationMs >= 5000 ? '⚠ ' : ''}${formatToolDuration(content.meta.durationMs)}`
            : ''}
        </summary>
        <div style={{ marginTop: 4, fontSize: 10, color: '#a0a0a0' }}>参数</div>
        <pre className="aw-tl-pre">{prettyJsonSafe(content.args)}</pre>
        <div style={{ marginTop: 4, fontSize: 10, color: '#a0a0a0' }}>结果</div>
        <pre className="aw-tl-pre">{prettyJsonSafe(content.artifact)}</pre>
      </details>
    </div>
  );
}

function isSucceededState(state?: string | null) {
  return String(state ?? '').toLowerCase() === 'succeeded';
}

function ProgressRow({ content, state }: { content: any; state?: string | null }) {
  const view = progressView(content);
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: '#f0f7ff',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <ToolIcon />
        <TypographyStrong>报告进度</TypographyStrong>
        {view.percent != null && (
          <span
            style={{
              fontSize: 10,
              color: '#1677ff',
              background: '#d6e4ff',
              borderRadius: 4,
              padding: '0 5px',
            }}
          >
            {view.percent}%
          </span>
        )}
        {!isSucceededState(state) && <StateTag state={state} />}
      </div>
      {view.summary && (
        <div style={{ marginTop: 5, fontSize: 12, color: '#434343' }}>
          {view.summary}
        </div>
      )}
      {view.nextAction && (
        <div style={{ marginTop: 3, fontSize: 11, color: '#8c8c8c' }}>
          下一步：{view.nextAction}
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
      <RawData content={content} />
    </div>
  );
}

function WebPageRow({
  content,
  state,
  kind,
}: {
  content: any;
  state?: string | null;
  kind: 'read' | 'fetch';
}) {
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
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <ToolIcon />
        <TypographyStrong>{content.displayName || (kind === 'read' ? '网页读取' : '网页获取')}</TypographyStrong>
        {view.statusCode != null && (
          <span
            style={{
              fontSize: 10,
              color: okStatus ? '#389e0d' : '#cf1322',
              background: okStatus ? '#d9f7be' : '#ffccc7',
              borderRadius: 4,
              padding: '0 5px',
            }}
          >
            {view.statusCode}
          </span>
        )}
        {!isSucceededState(state) && <StateTag state={state} />}
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
            正文 {view.chars.toLocaleString()} 字符
          </div>
        )
      )}
      {!view.error && (
        <div>
          <ContentPreview title={view.title || host || view.url} content={body} />
        </div>
      )}
      <RawData content={content} />
    </div>
  );
}

function SkillRow({
  content,
  state,
  kind,
}: {
  content: any;
  state?: string | null;
  kind: 'library' | 'manage';
}) {
  const view = skillView(content);
  const title = content.displayName || (kind === 'library' ? '技能库' : '技能管理');
  const summary =
    kind === 'library' && view.total != null
      ? `${view.total} 个技能`
      : view.action;
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: view.error ? '#fff1f0' : '#fafafa',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <ToolIcon />
        <TypographyStrong>{title}</TypographyStrong>
        <span
          style={{
            fontSize: 10,
            color: '#722ed1',
            background: '#f9f0ff',
            borderRadius: 4,
            padding: '0 5px',
          }}
        >
          {summary}
        </span>
        {!isSucceededState(state) && <StateTag state={state} />}
      </div>
      {view.preview && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#8c8c8c' }}>
          {view.preview.slice(0, 180)}
          {view.preview.length > 180 ? '…' : ''}
        </div>
      )}
      {view.names.length > 0 && (
        <div style={{ marginTop: 4, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
          {view.names.map((name) => (
            <span
              key={name}
              style={{
                fontSize: 10,
                border: '1px solid #efdbff',
                color: '#722ed1',
                borderRadius: 4,
                padding: '0 5px',
              }}
            >
              {name}
            </span>
          ))}
        </div>
      )}
      {view.error && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#cf1322' }}>
          {view.error}
        </div>
      )}
      <RawData content={content} />
    </div>
  );
}

function ReadToolResultRow({
  content,
  state,
}: {
  content: any;
  state?: string | null;
}) {
  const view = readToolResultView(content);
  const end = view.nextOffset ?? view.totalChars;
  return (
    <div
      style={{
        marginTop: 4,
        padding: '7px 10px',
        borderRadius: 8,
        background: view.error ? '#fff1f0' : '#fafafa',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <ToolIcon />
        <TypographyStrong>回读工具结果</TypographyStrong>
        {view.callId && (
          <span style={{ fontSize: 10, fontFamily: 'monospace', color: '#8c8c8c' }}>
            {view.callId}
          </span>
        )}
        {!isSucceededState(state) && <StateTag state={state} />}
      </div>
      {view.totalChars != null ? (
        <div style={{ marginTop: 4, fontSize: 11, color: '#8c8c8c' }}>
          字符 {view.offset ?? 0}–{end ?? view.totalChars} /{' '}
          {view.totalChars.toLocaleString()}
          {view.truncated ? ' · 已截断' : ''}
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
      <RawData content={content} />
    </div>
  );
}

function CliRow({ content, state }: { content: any; state?: string | null }) {
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
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <ToolIcon />
        <TypographyStrong>{content.displayName || '命令执行'}</TypographyStrong>
        {view.exitCode != null && (
          <span
            style={{
              fontSize: 10,
              color: view.exitCode === 0 ? '#389e0d' : '#cf1322',
              background: view.exitCode === 0 ? '#d9f7be' : '#ffccc7',
              borderRadius: 4,
              padding: '0 5px',
            }}
          >
            exit {view.exitCode}
          </span>
        )}
        {!isSucceededState(state) && <StateTag state={state} />}
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
        <ContentPreview title={content.displayName || '命令输出'} content={output} />
      </div>
      <RawData content={content} />
    </div>
  );
}

function BrowserRow({
  content,
  state,
  loadFile,
}: {
  content: any;
  state?: string | null;
  loadFile: (fileKey: string) => Promise<string>;
}) {
  const brow = browserResult(content, content.displayName);
  let args: any = {};
  let artifact: any = {};
  try {
    args = content.args ? JSON.parse(content.args) : {};
  } catch {
    // ignore
  }
  try {
    artifact = content.artifact ? JSON.parse(content.artifact) : {};
  } catch {
    // ignore
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
  const screenshot = artifact?.data?.screenshot || null;
  const screenshotSize =
    screenshot?.width && screenshot?.height
      ? `${screenshot.width}×${screenshot.height}`
      : '';
  const scopeLabel =
    args?.scope === 'full'
      ? '整页'
      : args?.scope === 'viewport'
        ? '视口'
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
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <ToolIcon />
        <TypographyStrong>浏览器</TypographyStrong>
        {action && (
          <span
            style={{
              fontSize: 10,
              color: '#1677ff',
              background: '#d6e4ff',
              borderRadius: 4,
              padding: '0 5px',
            }}
          >
            {action}
          </span>
        )}
        {brow.failed ? (
          <span
            style={{
              fontSize: 10,
              color: '#cf1322',
              background: '#ffccc7',
              borderRadius: 4,
              padding: '0 5px',
            }}
          >
            {brow.errorCategory || '失败'}
          </span>
        ) : (
          !isSucceededState(state) && <StateTag state={state} />
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
            {['截图', screenshotSize, scopeLabel].filter(Boolean).join(' · ')}
          </div>
          <UserImages
            images={screenshotImages}
            loadFile={loadFile}
            variant="screenshot"
          />
        </div>
      )}
      {(brow.method || brow.resultType) && (
        <div style={{ marginTop: 3, fontSize: 10.5, color: '#a0a0a0' }}>
          {[brow.method, brow.resultType].filter(Boolean).join(' · ')}
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
      <RawData content={content} />
    </div>
  );
}

function WaitRow({ content, state }: { content: any; state?: string | null }) {
  let seconds: number | null = null;
  try {
    const args = content.args ? JSON.parse(content.args) : null;
    if (args?.ms != null) {
      seconds = Math.max(1, Math.round(Number(args.ms) / 1000));
    }
  } catch {
    // ignore malformed args
  }
  const normalized = String(state ?? '').toLowerCase();
  const failed = normalized === 'failed' || normalized === 'error';
  const completed =
    !failed && ['succeeded', 'completed', 'success'].includes(normalized);
  const color = failed ? '#cf1322' : completed ? '#389e0d' : '#8c8c8c';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <ClockIcon />
      <span style={{ fontSize: 12, fontWeight: 600, color }}>
        {failed ? '等待失败' : completed ? '等待完成' : '等待中'}
      </span>
      {seconds != null && !failed ? (
        <span style={{ color: '#9ca3af', fontSize: 11 }}>{seconds}s</span>
      ) : null}
    </div>
  );
}

function CurrentTimeRow({ content, state }: { content: any; state?: string | null }) {
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
    ? time.replace('T', ' ').replace(/([+-]\d{2}:\d{2})$/, '').slice(0, 16)
    : '';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <ClockIcon />
      <TypographyStrong>当前时间</TypographyStrong>
      {display ? <span style={{ fontSize: 12 }}>· {display}</span> : null}
      {timezone ? <span style={{ color: '#9ca3af', fontSize: 11 }}>{timezone}</span> : null}
      <StateTag state={state} />
    </div>
  );
}

const APPROVAL_DECISION_LABEL: Record<string, string> = {
  ALLOW_ONCE: '允许一次',
  ALLOW_RUN: '本次消息内允许',
  ALLOW_SESSION: '本次会话内允许',
  DENY: '拒绝',
};

function ToolApprovalRow({ row }: { row: TimelineRow }) {
  const c = row.content || {};
  const calls = parseJsonArray(c.toolCalls);
  const decisions = parseJsonArray(c.decisions);
  const byCall = new Map<string, string>();
  for (const d of decisions) {
    if (d?.callId) byCall.set(String(d.callId), String(d.decision || 'DENY'));
  }
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <ApprovalIcon />
        <TypographyStrong>{c.text || row.title || '工具授权'}</TypographyStrong>
        <StateTag state={row.state} />
      </div>
      <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 4 }}>
        {calls.map((call: any, index: number) => {
          const decision = byCall.get(String(call.callId));
          return (
            <div key={String(call.callId || index)} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <ToolIcon />
              <span style={{ fontSize: 12 }}>
                {call.displayName || call.toolName || '工具'}
                {call.action ? ` · ${call.action}` : ''}
              </span>
              {decision ? (
                <span
                  style={{
                    fontSize: 11,
                    color: decision === 'DENY' ? '#cf1322' : '#1677ff',
                    border: `1px solid ${decision === 'DENY' ? '#ffccc7' : '#91caff'}`,
                    borderRadius: 4,
                    padding: '0 4px',
                  }}
                >
                  {APPROVAL_DECISION_LABEL[decision] || decision}
                </span>
              ) : (
                <span style={{ color: '#9ca3af', fontSize: 11 }}>等待处理</span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ClarifyRow({
  row,
}: {
  row: TimelineRow;
  onRespondClarify?: (runId: number, clarificationId: string, response: string) => void;
  onCancelClarify?: () => void;
}) {
  const c = row.content || {};
  const options = parseJsonArray(c.options);
  const description =
    typeof c.description === 'string' ? c.description.trim() : '';
  let selected: string[] = [];
  let text = '';
  if (c.response) {
    try {
      const parsed = JSON.parse(String(c.response));
      if (parsed && typeof parsed === 'object') {
        selected = Array.isArray(parsed.selected) ? parsed.selected.map(String) : [];
        text = typeof parsed.text === 'string' ? parsed.text : '';
      } else {
        text = String(c.response);
      }
    } catch {
      text = String(c.response);
    }
  }
  const cancelled = c.response === '__dismissed__' || row.state === 'CANCELLED';
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <MessageIcon />
        <strong>{String(c.title || row.title || '澄清')}</strong>
        <StateTag state={row.state} />
      </div>
      <div style={{ marginTop: 6, color: '#8c8c8c', display: 'flex', flexDirection: 'column', gap: 6 }}>
        {description ? (
          <div style={{ whiteSpace: 'pre-wrap', color: '#4b5563', fontSize: 12 }}>{description}</div>
        ) : null}
        {row.state === 'PENDING' ? (
          '请在底部浮层中作答'
        ) : (
          <>
            {options.length > 0 ? (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                {options.map((o: any, index: number) => {
                  const value = String(o?.value ?? o?.label ?? '');
                  const label = String(o?.label ?? value);
                  const isSelected = selected.includes(value);
                  return (
                    <span
                      key={`${value}-${index}`}
                      style={{
                        color: isSelected ? '#1677ff' : '#595959',
                        border: `1px solid ${isSelected ? '#91caff' : '#e5e7eb'}`,
                        background: isSelected ? '#e6f4ff' : 'transparent',
                        borderRadius: 4,
                        padding: '0 6px',
                        fontSize: 11,
                        opacity: isSelected || cancelled ? 1 : 0.7,
                      }}
                    >
                      {label}
                    </span>
                  );
                })}
              </div>
            ) : null}
            {text ? <span style={{ color: '#333' }}>{text}</span> : null}
            {cancelled ? (
              <span>已取消</span>
            ) : selected.length === 0 && !text ? (
              <span>已应答</span>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
}

function SubAgentLlmItem({ s }: { s: any }) {
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <BrainIcon />
        <strong style={{ fontSize: 12 }}>模型推理</strong>
        {s.modelName ? (
          <span
            style={{
              color: '#1677ff',
              border: '1px solid #1677ff33',
              background: '#1677ff12',
              borderRadius: 4,
              padding: '0 4px',
fontSize: 11,
            }}
          >
            {s.modelName}
          </span>
        ) : null}
        <StateTag
          state={s.success === false ? 'FAILED' : s.running ? 'RUNNING' : 'COMPLETED'}
        />
      </div>
      {s.reasoning ? (
        <div>
          <div className="aw-tl-thinking-toggle">Model Reason</div>
          <div className="aw-tl-thinking">{s.reasoning}</div>
        </div>
      ) : null}
      {s.reply ? (
        <div>
          <div className="aw-tl-thinking-toggle">回复</div>
          <div style={{ fontSize: 12 }}>
            <Md text={s.reply} />
          </div>
          {s.running && <span style={{ color: '#9ca3af', fontSize: 11 }}>正在生成…</span>}
        </div>
      ) : null}
    </div>
  );
}

function SubAgentToolItem({
  s,
  detailOpen,
  onDetailToggle,
  loadFile,
}: {
  s: any;
  detailOpen: boolean;
  onDetailToggle: () => void;
  loadFile: (fileKey: string) => Promise<string>;
}) {
  const reason = toolCallReason({ args: s.argumentsJson });
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <ToolIcon />
        <TypographyStrong>
          {s.displayNameCn || s.displayNameEn || s.toolName || '工具'}
        </TypographyStrong>
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
          理由：{reason.slice(0, 120)}
        </div>
      )}
      {Array.isArray(s.images) && s.images.length > 0 && (
        <UserImages
          images={s.images}
          loadFile={loadFile}
          variant={s.toolName === 'builtin_5' ? 'screenshot' : 'attachment'}
        />
      )}
      <button className="aw-tl-detail-toggle" onClick={onDetailToggle}>
        {detailOpen ? '▾' : '▸'} 详情
      </button>
      {detailOpen ? (
        <div>
          {s.argumentsJson ? (
            <pre className="aw-tl-pre">{prettyJson(s.argumentsJson)}</pre>
          ) : null}
          {s.artifact ? <pre className="aw-tl-pre">{prettyJson(s.artifact)}</pre> : null}
          {s.toolErrorMessage ? (
            <div style={{ fontSize: 11, color: '#cf1322' }}>{s.toolErrorMessage}</div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function SubAgentCard({
  row,
  subAgentLiveMap,
  loadSubAgentSteps,
  loadFile,
}: {
  row: TimelineRow;
  subAgentLiveMap: SubAgentLiveMap;
  loadSubAgentSteps: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  loadFile: (fileKey: string) => Promise<string>;
}) {
  const [open, setOpen] = useState(false);
  const [steps, setSteps] = useState<SubAgentTimelineItemVO[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [activeToolKey, setActiveToolKey] = useState<string | null>(null);
  const stepsBoxRef = useRef<HTMLDivElement | null>(null);
  const subId = row.refSubAgentRunId;
  const isRunning = row.state === 'RUNNING';
  const live = subId != null && isRunning ? (subAgentLiveMap[subId] ?? []) : [];

  const stepKey = (s: any, i: number) =>
    String(
      s?.interactionId ??
        s?.id ??
        s?.stepId ??
        (s?.type === 'tool_call' ? `live-tool-${s?.publishId}` : `live-llm-${i}`),
    );

  const list = useMemo<readonly any[]>(() => {
    const displaySteps = steps ?? (isRunning && live.length ? live : null);
    return displaySteps ?? [];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [steps, isRunning, live]);

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

  useEffect(() => {
    if (!open) return;
    const el = stepsBoxRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [open, live, steps]);

  const stepsRef = useRef<SubAgentTimelineItemVO[] | null>(null);
  stepsRef.current = steps;
  const sync = () => {
    if (!loadSubAgentSteps || subId == null) return;
    if (stepsRef.current === null) setLoading(true);
    void loadSubAgentSteps(Number(subId))
      .then((s) => setSteps(Array.isArray(s) ? s : []))
      .catch(() => setSteps([]))
      .finally(() => setLoading(false));
  };

  const expand = () => {
    setOpen((o) => !o);
    if (steps === null && !isRunning) sync();
  };

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
    <div className="aw-subagent">
      <div className="aw-subagent-head">
        <BrainIcon />
        <strong>
          {stripSubAgentMarkerPrefix(row.content?.displayName || row.title) ||
            '子 Agent'}
        </strong>
        <StateTag state={row.state} />
        {open && isRunning ? (
          <span style={{ color: '#9ca3af', fontSize: 11, display: 'inline-flex', alignItems: 'center', gap: 3 }}><RefreshIcon size={11} /> 实时更新中</span>
        ) : null}
      </div>
      <div style={{ marginTop: 4 }}>
        <button type="button" className="aw-subagent-expand" onClick={expand}>
          {open ? '▾' : '▸'} {open ? '收起步骤' : '展开步骤'}
        </button>
        {open ? (
          <div ref={stepsBoxRef} className="aw-subagent-steps">
            {loading && steps === null ? (
              <span className="aw-subagent-empty">加载中…</span>
            ) : list.length ? (
              list.map((s: any, i: number) => {
                const isTool =
                  s?.activityType === 'tool_call' || s?.type === 'tool_call';
                const key = stepKey(s, i);
                return (
                  <div key={key} className="aw-subagent-step">
                    {isTool ? (
                      <SubAgentToolItem
                        s={s}
                        detailOpen={activeToolKey === key}
                        onDetailToggle={() =>
                          setActiveToolKey((cur) => (cur === key ? null : key))
                        }
                        loadFile={loadFile}
                      />
                    ) : (
                      <SubAgentLlmItem s={s} />
                    )}
                  </div>
                );
              })
            ) : (
              <span className="aw-subagent-empty">
                {isRunning ? '（等待实时步骤…）' : '（无详细步骤）'}
              </span>
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- shell

function UserImages({
  images,
  loadFile,
  variant = 'attachment',
}: {
  images?: { fileKey: string; contentType?: string }[];
  loadFile: (fileKey: string) => Promise<string>;
  variant?: 'attachment' | 'screenshot';
}) {
  const [urls, setUrls] = useState<Record<string, string>>({});
  // loadFile 经 ref 读取：不进入 effect 依赖，避免上层内联函数身份变化引发无限重取
  const loadFileRef = useRef(loadFile);
  loadFileRef.current = loadFile;
  const imagesKey = (images || [])
    .map((im) => im.fileKey || '')
    .join(',');
  useEffect(() => {
    // 仅按 fileKey 集合变化响应式重取（objectURL 缓存由 fileCache 统一持有，组件不再 revoke）
    const list = (images || []).filter((im) => im.fileKey);
    if (list.length === 0) return;
    let alive = true;
    Promise.all(
      list.map(async (im) => {
        try {
          return [im.fileKey, await loadFileRef.current(im.fileKey)] as const;
        } catch (err) {
          console.warn('[widget] load image failed', im.fileKey, err);
          return [im.fileKey, ''] as const;
        }
      }),
    ).then((entries) => {
      if (!alive) return;
      const next: Record<string, string> = {};
      for (const [key, url] of entries) {
        if (url) next[key] = url;
      }
      // 内容不变则不 setState，避免无谓重渲染
      setUrls((prev) => {
        const pk = Object.keys(prev);
        const nk = Object.keys(next);
        if (pk.length === nk.length && nk.every((k) => prev[k] === next[k])) {
          return prev;
        }
        return next;
      });
    });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imagesKey]);
  const list = (images || []).filter((im) => im.fileKey && urls[im.fileKey]);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  if (list.length === 0) return null;
  return (
    <div className="aw-tl-user-images">
      {list.map((im, idx) => (
        <img
          key={im.fileKey || idx}
          src={urls[im.fileKey]}
          alt="attachment"
          className={
            variant === 'screenshot'
              ? 'aw-tl-user-img aw-tl-user-img-screenshot'
              : 'aw-tl-user-img'
          }
          style={{ cursor: 'pointer' }}
          onClick={() => setPreviewUrl(urls[im.fileKey])}
        />
      ))}
      {previewUrl ? (
        <div className="aw-tl-image-preview" onClick={() => setPreviewUrl(null)}>
          <img src={previewUrl} alt="preview" className="aw-tl-image-preview-img" />
          <button type="button" className="aw-tl-image-preview-close" aria-label="关闭">
            ✕
          </button>
        </div>
      ) : null}
    </div>
  );
}

function RowView({
  row,
  subAgentLiveMap,
  loadSubAgentSteps,
  loadFile,
  onRespondClarify,
  onCancelClarify,
}: {
  row: TimelineRow;
  subAgentLiveMap: SubAgentLiveMap;
  loadSubAgentSteps: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  loadFile: (fileKey: string) => Promise<string>;
  onRespondClarify: (runId: number, clarificationId: string, response: string) => void;
  onCancelClarify?: () => void;
}) {
  const c = row.content || {};
  const isUser = row.kind === 'user';
  const isAssistant = row.kind === 'assistant';

  if (row.kind === 'compaction') {
    const text = c.text || '';
    const running = row.state === 'RUNNING';
    const failed = row.state === 'FAILED' || row.state === 'failed';
    return (
      <div className="aw-tl-compaction">
        <div className="aw-tl-divider">
          {running ? (
            <span className="aw-running-dot" />
          ) : failed ? (
            <CloseIcon size={12} className="aw-icon-failed" />
          ) : (
            <RefreshIcon />
          )}
          <span>{row.title || '上下文压缩'}</span>
        </div>
        {text ? (
          <div className="aw-tl-compaction-body">
            <Md text={text} />
          </div>
        ) : running ? (
          <div className="aw-tl-compaction-hint">（压缩中…）</div>
        ) : null}
      </div>
    );
  }

  if (row.kind === 'run_status') {
    const duration = formatDuration(c.durationMs);
    const rawText = String(c.text || row.title || '');
    // 剥离后端状态前缀（❌/⏹️/⏸️），失败用红叉表示
    const text = rawText.replace(/^[❌⏹️⏸️]\s*/u, '');
    const parts = [text, duration, c.modelName].filter(
      (p): p is string => !!p,
    );
    const failed =
      /fail|失败|错误/i.test(text) || String(row.state) === 'FAILED';
    return (
      <div className="aw-tl-divider">
        {row.state === 'RUNNING' ? (
          <span className="aw-running-dot" />
        ) : failed ? (
          <CloseIcon size={12} className="aw-icon-failed" />
        ) : row.state === 'AWAITING_USER' ? (
          <PauseIcon size={12} />
        ) : (
          <CheckIcon size={12} />
        )}
        <span>{parts.join(' · ') || text}</span>
        <UsageChip usage={c.usage ?? undefined} />
      </div>
    );
  }

  const body = (() => {
    switch (row.kind) {
      case 'user':
        return (
          <span className="aw-tl-user-body">
            <UserImages images={c.images} loadFile={loadFile} />
            <span>{c.text || row.title || ''}</span>
          </span>
        );
      case 'assistant':
        return <AssistantRow row={row} />;
      case 'tool':
        return <ToolRow row={row} loadFile={loadFile} />;
      case 'tool_approval':
        return <ToolApprovalRow row={row} />;
      case 'clarification':
        return (
          <ClarifyRow
            row={row}
            onRespondClarify={onRespondClarify}
            onCancelClarify={onCancelClarify}
          />
        );
      case 'subagent':
        return (
          <SubAgentCard
            row={row}
            subAgentLiveMap={subAgentLiveMap}
            loadSubAgentSteps={loadSubAgentSteps}
            loadFile={loadFile}
          />
        );
      case 'error':
        return <span style={{ color: '#9ca3af' }}>{c.text || row.title}</span>;
      default:
        return <span>{c.text || row.title || ''}</span>;
    }
  })();

  if (isUser) {
    return (
      <div className="aw-tl-user aw-tl-copy-hover">
        <div>
          <div className="aw-tl-user-bubble">{body}</div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <CopyBtn text={c.text || row.title || ''} />
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className={`aw-tl-card${isAssistant ? ' aw-tl-copy-hover' : ''}`}>
      {body}
    </div>
  );
}

/** 并行子 Agent：同一 parentToolCallId 的多个 subagent 行合并为横向 Tab。 */
function ParallelSubAgentTabs({
  members,
  subAgentLiveMap,
  loadSubAgentSteps,
  loadFile,
}: {
  members: TimelineRow[];
  subAgentLiveMap: SubAgentLiveMap;
  loadSubAgentSteps: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  loadFile: (fileKey: string) => Promise<string>;
}) {
  const [active, setActive] = useState(0);
  const idx = Math.min(active, members.length - 1);
  return (
    <div className="aw-subagent-tabs">
      <div className="aw-subagent-tabbar">
        {members.map((m, i) => (
          <button
            key={m.seq}
            type="button"
            className={`aw-subagent-tab${i === idx ? ' active' : ''}`}
            onClick={() => setActive(i)}
          >
            {stripSubAgentMarkerPrefix(m.content?.displayName || m.title) ||
              `Agent ${i + 1}`}
          </button>
        ))}
      </div>
      <SubAgentCard
        row={members[idx]}
        subAgentLiveMap={subAgentLiveMap}
        loadSubAgentSteps={loadSubAgentSteps}
        loadFile={loadFile}
      />
    </div>
  );
}

export function WidgetTimeline({
  rows,
  hasMore,
  loadingOlder,
  onLoadOlder,
  pendingUserRows = [],
  subAgentLiveMap,
  loadSubAgentSteps,
  loadFile,
  onRespondClarify,
  onCancelClarify,
  preparingTool,
}: WidgetTimelineProps) {
  // 同一 parentToolCallId 的多个 subagent 行（≥2）合并为并行 Tab
  const items = useMemo(() => {
    const groups = new Map<string, TimelineRow[]>();
    for (const r of rows) {
      if (isInternalToolRow(r)) {
        continue;
      }
      if (r.kind !== 'subagent') continue;
      const pid = r.content?.parentToolCallId;
      if (pid == null || pid === '') continue;
      const k = String(pid);
      const arr = groups.get(k) ?? [];
      arr.push(r);
      groups.set(k, arr);
    }
    const multi = new Set<string>();
    for (const [k, v] of groups) {
      if (v.length >= 2) multi.add(k);
    }
    const emitted = new Set<string>();
    const out: ReactNode[] = [];
    for (const row of rows) {
      const pid = row.kind === 'subagent' ? row.content?.parentToolCallId : null;
      const k = pid != null && pid !== '' ? String(pid) : '';
      if (k && multi.has(k)) {
        if (emitted.has(k)) continue;
        emitted.add(k);
        out.push(
          <div key={`grp-${k}`} className="aw-tl-row">
            <ParallelSubAgentTabs
              members={groups.get(k)!}
              subAgentLiveMap={subAgentLiveMap}
              loadSubAgentSteps={loadSubAgentSteps}
              loadFile={loadFile}
            />
          </div>,
        );
        continue;
      }
      const key =
        row.seq < 0 ? `p-${row.refSubAgentRunId ?? 'x'}` : String(row.seq);
      out.push(
        <div key={key} className="aw-tl-row">
          <RowView
            row={row}
            subAgentLiveMap={subAgentLiveMap}
            loadSubAgentSteps={loadSubAgentSteps}
            loadFile={loadFile}
            onRespondClarify={onRespondClarify}
            onCancelClarify={onCancelClarify}
          />
        </div>,
      );
    }
    return out;
  }, [rows, subAgentLiveMap, loadSubAgentSteps, loadFile, onRespondClarify, onCancelClarify]);

  return (
    <div className="aw-tl">
      {hasMore ? (
        <button
          type="button"
          className="aw-tl-older"
          disabled={loadingOlder}
          onClick={onLoadOlder}
        >
          {loadingOlder ? '正在加载更早消息…' : '加载更早消息'}
        </button>
      ) : null}
      {rows.length === 0 && pendingUserRows.length === 0 ? (
        <div className="aw-tl-empty">（暂无消息）</div>
      ) : (
        <>
          {items}
          {pendingUserRows.map((row) => (
            <div key={`local-${row.seq}`} className="aw-tl-row">
              <RowView
                row={row}
                subAgentLiveMap={subAgentLiveMap}
                loadSubAgentSteps={loadSubAgentSteps}
                loadFile={loadFile}
                onRespondClarify={onRespondClarify}
                onCancelClarify={onCancelClarify}
              />
            </div>
          ))}
        </>
      )}
      {preparingTool ? (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            padding: '4px 0',
            color: '#6b7280',
            fontSize: 12,
          }}
        >
          <RefreshIcon />
          <span>{preparingTool.name || '工具'} · 正在生成调用参数…</span>
        </div>
      ) : null}
    </div>
  );
}
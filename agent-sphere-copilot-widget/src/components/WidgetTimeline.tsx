import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Markdown } from '../markdown';
import { CheckIcon, CopyIcon } from '../icons';
import { stripSubAgentMarkerPrefix } from '../subAgentMarker';
import { browserResult, isBrowserTool, toolFamily } from '../toolRenderers';
import { DocCard, GenericCard, TodoCard } from './ToolCard';
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
}

// ---------------------------------------------------------------- utils

// finish 是内部协议工具（builtin_12）：答案已作为助手正文展示，工具行不再渲染（与主站一致）。
const FINISH_TOOL_NAME = 'builtin_12';

function isFinishToolRow(row: TimelineRow): boolean {
  if (!row || row.kind !== 'tool') {
    return false;
  }
  const content = row.content;
  return (
    content?.toolName === FINISH_TOOL_NAME ||
    content?.displayName === '完成任务' ||
    content?.displayName === 'Finish'
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

function parseOptions(raw?: string | null): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.map((o: unknown) =>
        typeof o === 'string'
          ? o
          : ((o as { value?: unknown; label?: unknown })?.value ??
            (o as { label?: unknown })?.label),
      ).filter((v: unknown): v is string => typeof v === 'string' && !!v);
    }
  } catch {
    // ignore
  }
  return [];
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
  const [thinkingOpen, setThinkingOpen] = useState(true);
  const [autoCollapsed, setAutoCollapsed] = useState(false);
  const thinkBoxRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!autoCollapsed && reply.trim().length > 0) {
      setAutoCollapsed(true);
      setThinkingOpen(false);
    }
  }, [reply, autoCollapsed]);
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

function ToolRow({
  row,
  loadFile,
}: {
  row: TimelineRow;
  loadFile: (fileKey: string) => Promise<string>;
}) {
  const c = row.content || {};
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
          <span style={{ opacity: 0.7 }}>🛠️</span>
          <TypographyStrong>{name}</TypographyStrong>
          {family !== 'todo' &&
            (stateLabel ? (
              <span className="aw-tool-failed">✕ {stateLabel}</span>
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

function TypographyStrong({ children }: { children: ReactNode }) {
  return (
    <strong style={{ fontSize: 12, color: '#8c8c8c' }}>{children}</strong>
  );
}

function ClarifyRow({
  row,
  onRespondClarify,
  onCancelClarify,
}: {
  row: TimelineRow;
  onRespondClarify: (runId: number, clarificationId: string, response: string) => void;
  onCancelClarify?: () => void;
}) {
  const c = row.content || {};
  const [text, setText] = useState('');
  const options = useMemo(() => parseOptions(c.options), [c.options]);
  const runId = row.runId ?? row.refRunId;
  const clarificationId = String(
    c.clarificationId || row.refClarificationId || '',
  );
  const respond = (value: string) => {
    const v = String(value ?? '').trim();
    if (!v || runId == null) return;
    onRespondClarify(runId, clarificationId, v);
    setText('');
  };
  return (
    <div>
      <strong>{String(c.title || row.title || '澄清')}</strong>{' '}
      <StateTag state={row.state} />
      {row.state === 'PENDING' ? (
        <div className="aw-tl-clarify-options">
          {options.length === 0 && (
            <button
              type="button"
              className="aw-tl-clarify-option"
              onClick={() => respond('confirmed')}
            >
              确认
            </button>
          )}
          {options.map((o) => (
            <button
              key={o}
              type="button"
              className="aw-tl-clarify-option"
              onClick={() => respond(o)}
            >
              {o}
            </button>
          ))}
          <div className="aw-clarify-answer-wrap" style={{ width: '100%' }}>
            <input
              className="aw-clarify-answer-input"
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="输入澄清内容…"
            />
            <button
              type="button"
              className="aw-clarify-submit"
              disabled={!text.trim()}
              onClick={() => respond(text)}
            >
              提交
            </button>
            <button
              type="button"
              className="aw-clarify-submit"
              style={{ background: '#fff', color: '#8c8c8c' }}
              onClick={() => onCancelClarify?.()}
            >
              取消
            </button>
          </div>
        </div>
      ) : (
        <div style={{ marginTop: 6 }}>
          <span style={{ color: '#9ca3af' }}>已应答</span>
          {c.response ? <span>：{String(c.response)}</span> : null}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- sub-agent

function SubAgentLlmItem({ s }: { s: any }) {
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span>🧠</span>
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
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span>🛠️</span>
        <TypographyStrong>
          {s.displayNameCn || s.displayNameEn || s.toolName || '工具'}
        </TypographyStrong>
        <StateTag state={s.toolStatus || s.status} />
      </div>
      {Array.isArray(s.images) && s.images.length > 0 && (
        <UserImages images={s.images} loadFile={loadFile} />
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
        <span>⚙️</span>
        <strong>
          {stripSubAgentMarkerPrefix(row.content?.displayName || row.title) ||
            '子 Agent'}
        </strong>
        <StateTag state={row.state} />
        {open && isRunning ? (
          <span style={{ color: '#9ca3af', fontSize: 11 }}>⟳ 实时更新中</span>
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
}: {
  images?: { fileKey: string; contentType?: string }[];
  loadFile: (fileKey: string) => Promise<string>;
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
          className="aw-tl-user-img"
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
          <span style={{ color: '#ff4d4f' }}>✕</span>
        ) : (
          <span>•</span>
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
}: WidgetTimelineProps) {
  // 同一 parentToolCallId 的多个 subagent 行（≥2）合并为并行 Tab
  const items = useMemo(() => {
    const groups = new Map<string, TimelineRow[]>();
    for (const r of rows) {
      if (isFinishToolRow(r)) {
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
    </div>
  );
}
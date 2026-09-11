import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Markdown } from '../markdown';
import { CheckIcon, CopyIcon } from '../icons';
import { stripSubAgentMarkerPrefix } from '../subAgentMarker';
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
}

// ---------------------------------------------------------------- utils

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
  useEffect(() => {
    if (!autoCollapsed && reply.trim().length > 0) {
      setAutoCollapsed(true);
      setThinkingOpen(false);
    }
  }, [reply, autoCollapsed]);
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
        <div className="aw-tl-thinking">{thinking || '（无推理内容）'}</div>
      )}
      <div className="aw-tl-reply">
        {reply ? (
          <Md text={reply} />
        ) : (
          <span style={{ color: '#9ca3af' }}>（流式中…）</span>
        )}
        <CopyBtn text={reply} />
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
  const [open, setOpen] = useState(false);
  return (
    <div>
      <div className="aw-tl-tool-head">
        <span style={{ opacity: 0.7 }}>🛠️</span>
        <TypographyStrong>{c.displayName || row.title || 'tool'}</TypographyStrong>
        <StateTag state={row.state} />
      </div>
      {Array.isArray(c.images) && c.images.length > 0 && (
        <UserImages images={c.images} loadFile={loadFile} />
      )}
      <div>
        <button
          type="button"
          className="aw-tl-detail-toggle"
          onClick={() => setOpen((o) => !o)}
        >
          {open ? '▾' : '▸'} 详情
        </button>
        {open && (
          <div>
            {c.args && <pre className="aw-tl-pre">{prettyJson(c.args)}</pre>}
            {c.artifact && <pre className="aw-tl-pre">{c.artifact}</pre>}
          </div>
        )}
      </div>
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
}: {
  row: TimelineRow;
  onRespondClarify: (runId: number, clarificationId: string, response: string) => void;
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
          {s.artifact ? <pre className="aw-tl-pre">{s.artifact}</pre> : null}
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
  const currentUrlsRef = useRef<Record<string, string>>({});
  const imagesKey = (images || [])
    .map((im) => im.fileKey || '')
    .join(',');
  useEffect(() => {
    // 按 fileKey 集合变化响应式重取：权威行替换乐观行时迟到补上的 images 也能加载
    const list = (images || []).filter((im) => im.fileKey);
    if (list.length === 0) return;
    let alive = true;
    const next: Record<string, string> = {};
    Promise.all(
      list.map(async (im) => {
        try {
          next[im.fileKey] = await loadFile(im.fileKey);
        } catch (err) {
          console.warn('[widget] load image failed', im.fileKey, err);
        }
      }),
    ).then(() => {
      if (!alive) return;
      // 新 URL 就绪后再释放旧 URL，避免短暂渲染破损图
      Object.values(currentUrlsRef.current).forEach((u) => {
        try {
          URL.revokeObjectURL(u);
        } catch {
          /* 忽略 */
        }
      });
      currentUrlsRef.current = next;
      setUrls(next);
    });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imagesKey, loadFile]);
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
}: {
  row: TimelineRow;
  subAgentLiveMap: SubAgentLiveMap;
  loadSubAgentSteps: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  loadFile: (fileKey: string) => Promise<string>;
  onRespondClarify: (runId: number, clarificationId: string, response: string) => void;
}) {
  const c = row.content || {};
  const isUser = row.kind === 'user';
  const isAssistant = row.kind === 'assistant';

  if (row.kind === 'run_status') {
    const duration = formatDuration(c.durationMs);
    const parts = [c.text || row.title, duration, c.modelName].filter(
      (p): p is string => !!p,
    );
    return (
      <div className="aw-tl-divider">
        {row.state === 'RUNNING' ? <span className="aw-running-dot" /> : <span>•</span>}
        {parts.join(' · ') || row.title}
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
        return <ClarifyRow row={row} onRespondClarify={onRespondClarify} />;
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
}: WidgetTimelineProps) {
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
          {rows.map((row) => (
            <div
              key={
                row.seq < 0
                  ? `p-${row.refSubAgentRunId ?? 'x'}`
                  : String(row.seq)
              }
              className="aw-tl-row"
            >
              <RowView
                row={row}
                subAgentLiveMap={subAgentLiveMap}
                loadSubAgentSteps={loadSubAgentSteps}
                loadFile={loadFile}
                onRespondClarify={onRespondClarify}
              />
            </div>
          ))}
          {pendingUserRows.map((row) => (
            <div key={`local-${row.seq}`} className="aw-tl-row">
              <RowView
                row={row}
                subAgentLiveMap={subAgentLiveMap}
                loadSubAgentSteps={loadSubAgentSteps}
                loadFile={loadFile}
                onRespondClarify={onRespondClarify}
              />
            </div>
          ))}
        </>
      )}
    </div>
  );
}
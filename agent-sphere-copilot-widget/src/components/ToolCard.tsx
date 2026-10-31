/**
 * 工具卡家族渲染（自 UmiJS 主站 ToolCard 移植，去除 antd 依赖）。
 * 与 `toolRenderers.ts` 的纯判别函数配合：todo/doc/generic 三类 + 可折叠 JSON 块。
 */
import { useState } from 'react';
import { DocIcon } from '../icons';
import {
  docSummary,
  docSummaryZh,
  extractTodos,
  genericSummary,
  todoProgress,
  todoTotal,
  type TodoItem,
  type ToolContent,
} from '../toolRenderers';

export function prettyJson(raw?: string | null): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/** 参数 + 结果收口为一个折叠块，避免每个工具占两行裸 JSON。 */
export function formatToolDuration(ms?: number) {
  if (typeof ms !== 'number' || ms < 0) return '';
  if (ms < 1000) return `${ms}ms`;
  const seconds = ms / 1000;
  if (seconds < 60) {
    return `${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)}s`;
  }
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m${Math.round(seconds % 60)}s`;
}

/** 长内容预览弹层（widget 无 antd，使用 shadow DOM 内的 fixed 浮层）。 */
export function ContentPreview({
  title,
  content,
}: {
  title: string;
  content?: string | null;
}) {
  const [open, setOpen] = useState(false);
  if (!content || content.length < 200) return null;
  return (
    <>
      <button
        type="button"
        className="aw-tool-link"
        style={{
          padding: 0,
          border: 'none',
          background: 'transparent',
          color: '#1677ff',
          cursor: 'pointer',
          fontSize: 11,
        }}
        onClick={() => setOpen(true)}
      >
        预览全文（{content.length.toLocaleString()} 字符）
      </button>
      {open && (
        <div
          role="dialog"
          aria-modal="true"
          onClick={() => setOpen(false)}
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 9999,
            background: 'rgba(0,0,0,0.45)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 16,
          }}
        >
          <div
            onClick={(event) => event.stopPropagation()}
            style={{
              width: '100%',
              maxWidth: 720,
              maxHeight: '80vh',
              display: 'flex',
              flexDirection: 'column',
              background: '#fff',
              borderRadius: 8,
              boxShadow: '0 8px 30px rgba(0,0,0,0.2)',
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 8,
                padding: '10px 14px',
                borderBottom: '1px solid #f0f0f0',
                fontSize: 13,
                fontWeight: 600,
              }}
            >
              <span
                style={{
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {title}
              </span>
              <button
                type="button"
                onClick={() => setOpen(false)}
                style={{
                  border: 'none',
                  background: 'transparent',
                  cursor: 'pointer',
                  fontSize: 16,
                  lineHeight: 1,
                  color: '#8c8c8c',
                }}
                aria-label="close"
              >
                ×
              </button>
            </div>
            <pre
              style={{
                margin: 0,
                padding: '10px 14px',
                overflow: 'auto',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                fontSize: 12,
                lineHeight: 1.6,
              }}
            >
              {content}
            </pre>
          </div>
        </div>
      )}
    </>
  );
}

export function RawData({
  content,
  label = '查看原始数据',
}: {
  content: ToolContent;
  label?: string;
}) {
  return (
    <details className="aw-tool-block" style={{ marginTop: 5 }}>
      <summary
        style={{
          cursor: 'pointer',
          fontSize: 10.5,
          color: '#a0a0a0',
          userSelect: 'none',
        }}
      >
        {label}
      </summary>
      <div style={{ marginTop: 4, fontSize: 10, color: '#a0a0a0' }}>参数</div>
      <pre className="aw-tl-pre">{prettyJson(content.args)}</pre>
      <div style={{ marginTop: 4, fontSize: 10, color: '#a0a0a0' }}>结果</div>
      <pre className="aw-tl-pre">{prettyJson(content.artifact)}</pre>
    </details>
  );
}

const PRIORITY_ORDER: Record<string, number> = { high: 0, medium: 1, low: 2 };

function sortTodos(todos: TodoItem[]): TodoItem[] {
  return [...todos].sort(
    (a, b) =>
      (PRIORITY_ORDER[a.priority || ''] ?? 9) -
      (PRIORITY_ORDER[b.priority || ''] ?? 9),
  );
}

/** 待办工具卡：进度 done/total + 勾选态 + 高优先级标记 + 原始待办折叠。 */
export function TodoCard({ content }: { content: ToolContent }) {
  const todos = extractTodos(content);
  const total = todoTotal(content);
  const { done } = todoProgress(todos);
  const sorted = sortTodos(todos);
  return (
    <div>
      <div className="aw-tool-todo-progress">
        ☑ {done}/{total} 已完成
      </div>
      <div>
        {sorted.map((t, i) => (
          <div key={`${t.content}-${i}`} className="aw-tool-todo-item">
            <span
              className={
                t.status === 'completed'
                  ? 'aw-tool-todo-check done'
                  : 'aw-tool-todo-check'
              }
            >
              {t.status === 'completed' ? '✓' : ''}
            </span>
            <span
              className={
                t.status === 'completed'
                  ? 'aw-tool-todo-text done'
                  : 'aw-tool-todo-text'
              }
            >
              {t.content}
            </span>
            {t.priority === 'high' && (
              <span className="aw-tool-todo-pri">高</span>
            )}
            {(t.skillId != null || t.stepId) && (
              <span
                className="aw-tool-todo-skill"
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
              </span>
            )}
          </div>
        ))}
      </div>
      <RawData content={content} />
    </div>
  );
}

/** 文档工具卡：摘要 + artifact preview（不跳转，仅展示）。 */
export function DocCard({ content }: { content: ToolContent }) {
  const summary = docSummaryZh(content) || docSummary(content);
  let preview = '';
  let detail = '';
  let documentContent = '';
  let documentTitle = '';
  try {
    const art = content.artifact ? JSON.parse(content.artifact) : null;
    if (art && typeof art.preview === 'string') preview = art.preview;
    if (art && typeof art.title === 'string') documentTitle = art.title;
    const args = content.args ? JSON.parse(content.args) : null;
    const meta = content.meta || {};
    const chars =
      typeof meta.chars === 'number'
        ? meta.chars
        : art && typeof art.content === 'string'
          ? art.content.length
          : args && typeof args.content === 'string'
            ? args.content.length
            : null;
    const documentId =
      meta.documentId != null ? meta.documentId : art?.documentId;
    const version = meta.version != null ? meta.version : art?.version;
    const totalLines =
      meta.totalLines != null ? meta.totalLines : art?.totalLines;
    documentContent =
      art && typeof art.content === 'string'
        ? art.content
        : args && typeof args.content === 'string'
          ? args.content
          : '';
    detail = [
      documentId != null ? `documentId=${documentId}` : null,
      version != null ? `v${version}` : null,
      chars != null ? `正文 ${chars.toLocaleString()} 字符` : null,
      typeof totalLines === 'number' ? `${totalLines} 行` : null,
    ]
      .filter(Boolean)
      .join(' · ');
  } catch {
    /* 忽略 */
  }
  return (
    <div className="aw-tool-doc">
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        <DocIcon />
        <span
          title={summary}
          style={{
            minWidth: 0,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {summary}
        </span>
      </div>
      {detail && (
        <div style={{ fontSize: 11, color: '#9ca3af' }}>{detail}</div>
      )}
      {preview && <div className="aw-tool-doc-preview">{preview.slice(0, 60)}</div>}
      <div>
        <ContentPreview title={documentTitle || '文档'} content={documentContent} />
      </div>
      <RawData content={content} />
    </div>
  );
}

/** 通用工具卡：动作摘要 + 可折叠参数/结果（hideJson 时隐藏原始 JSON）。 */
export function GenericCard({
  content,
  hideJson,
}: {
  content: ToolContent;
  hideJson?: boolean;
}) {
  const summary = genericSummary(content);
  return (
    <div>
      <div className="aw-tool-summary">{summary}</div>
      {!hideJson && <RawData content={content} />}
    </div>
  );
}

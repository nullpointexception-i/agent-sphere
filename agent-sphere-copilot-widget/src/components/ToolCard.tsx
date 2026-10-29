/**
 * 工具卡家族渲染（自 UmiJS 主站 ToolCard 移植，去除 antd 依赖）。
 * 与 `toolRenderers.ts` 的纯判别函数配合：todo/doc/generic 三类 + 可折叠 JSON 块。
 */
import { useState } from 'react';
import type { ReactNode } from 'react';
import { DocIcon } from '../icons';
import {
  docSummary,
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

/** 可折叠块（默认关闭），标题点击开合。 */
export function Block({
  title,
  defaultOpen = false,
  children,
}: {
  title: ReactNode;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="aw-tool-block">
      <button
        type="button"
        className="aw-tl-detail-toggle"
        onClick={() => setOpen((o) => !o)}
      >
        {open ? '▾' : '▸'} {title}
      </button>
      {open && <div>{children}</div>}
    </div>
  );
}

/** JSON 折叠块（美化后展示）。 */
export function JsonBlock({
  label,
  json,
}: {
  label: string;
  json?: string | null;
}) {
  if (!json) return null;
  return (
    <Block title={label}>
      <pre className="aw-tl-pre">{prettyJson(json)}</pre>
    </Block>
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
      <JsonBlock label="查看原始待办" json={content.args || content.artifact} />
    </div>
  );
}

/** 文档工具卡：摘要 + artifact preview（不跳转，仅展示）。 */
export function DocCard({ content }: { content: ToolContent }) {
  const summary = docSummary(content);
  let preview = '';
  try {
    const art = content.artifact ? JSON.parse(content.artifact) : null;
    if (art && typeof art.preview === 'string') preview = art.preview;
  } catch {
    /* 忽略 */
  }
  return (
    <div className="aw-tool-doc">
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <DocIcon />
        <span>{summary}</span>
      </div>
      {preview && <div className="aw-tool-doc-preview">{preview.slice(0, 60)}</div>}
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
      {!hideJson && <JsonBlock label="查看原始参数" json={content.args} />}
      {!hideJson && <JsonBlock label="查看原始结果" json={content.artifact} />}
    </div>
  );
}

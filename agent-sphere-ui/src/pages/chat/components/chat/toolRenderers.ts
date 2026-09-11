/**
 * 工具卡友好渲染的纯函数判别/摘要逻辑。
 * 不依赖 React / antd，便于 jest 单测。
 *
 * 依据后端 AgentTimelineVO.content（tool 行）字段：
 *   displayName, status, args(JSON 字符串), artifact(JSON 字符串)
 * - todo 工具 args: { "todos": [{ "content","status","priority" }] }
 *   status ∈ pending|in_progress|completed|cancelled; priority ∈ high|medium|low
 * - doc 工具 args: { "action","title","content","documentId", ... }  action ∈ create|update|read|...
 */

export interface TodoItem {
  content: string;
  status?: string;
  priority?: string;
}

export interface ToolContent {
  displayName?: string;
  /** 工具名（后端字段名） */
  toolName?: string;
  /** 工具状态/结果状态 */
  status?: string;
  /** 调用参数，JSON 字符串 */
  args?: string;
  /** 执行产物，JSON 字符串 */
  artifact?: string;
}

export type ToolFamily = 'todo' | 'doc' | 'other';

const TODO_KEYWORD = /待办|todo/i;
const DOC_KEYWORD = /文档|document|docwrite/i;

function safeParseJson(s?: string): any {
  if (!s) return null;
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

/** 从 content 的 args / artifact 解析出原始对象（已 JSON.parse）。 */
function parsePayload(content: ToolContent): any {
  const args = safeParseJson(content?.args);
  if (args) return args;
  return safeParseJson(content?.artifact);
}

/**
 * 后端 ToolResultCompressor 会把 JSON 数组压缩成对象
 * { "_count": N, "_showing": N, "items": [前 N 项] }（空数组 → items:[]）。
 * 此函数将压缩后的数组形态还原为真实数组。
 */
function asArray(v: any): any[] | null {
  if (Array.isArray(v)) return v;
  if (v && typeof v === 'object' && Array.isArray(v.items)) return v.items;
  return null;
}

/** 提取 todos 列表（优先 args，其次 artifact）。兼容压缩数组形态。 */
export function extractTodos(content: ToolContent): TodoItem[] {
  const payload = parsePayload(content);
  if (!payload) return [];
  const todosSource = asArray(payload.todos) ?? asArray(payload.items);
  if (!todosSource) return [];
  return todosSource.map((t: any) => ({
    content:
      typeof t?.content === 'string'
        ? t.content
        : typeof t === 'string'
          ? t
          : '未命名任务',
    status: t?.status,
    priority: t?.priority,
  }));
}

/**
 * 判别工具家族：
 * - args 含 todos 数组 → todo
 * - args 含 action + (title | documentId | content) → doc（或 displayName 提示文档）
 * - 其余 → other
 */
export function toolFamily(
  content: ToolContent,
  displayName?: string,
): ToolFamily {
  const payload = parsePayload(content);
  const name = displayName || content?.displayName || '';
  if (payload && asArray(payload.todos)) return 'todo';
  if (payload && typeof payload.action === 'string') {
    const docHints = payload.title || payload.documentId || payload.content;
    if (docHints) return 'doc';
  }
  if (DOC_KEYWORD.test(name)) return 'doc';
  if (TODO_KEYWORD.test(name)) return 'todo';
  return 'other';
}

/** 文档工具摘要：`create document <title>`。 */
export function docSummary(content: ToolContent): string {
  const payload = parsePayload(content);
  const action = typeof payload?.action === 'string' ? payload.action : '操作';
  const title =
    typeof payload?.title === 'string' && payload.title.trim()
      ? payload.title.trim()
      : typeof payload?.documentId === 'string' ||
          typeof payload?.documentId === 'number'
        ? `#${payload.documentId}`
        : '';
  return title
    ? `${action} document${title.startsWith('#') ? '' : ' '}${title}`
    : `${action} document`;
}

/** 通用工具摘要：基于 args 关键字段推导动作；无规则时回退显示工具名。 */
export function genericSummary(content: ToolContent): string {
  const payload = parsePayload(content);
  if (!payload) return content?.displayName || '工具';
  const actionKey =
    payload.action ||
    payload.operation ||
    payload.tool ||
    payload.name ||
    content?.toolName;
  const subject =
    payload.query ||
    payload.keyword ||
    payload.url ||
    payload.path ||
    payload.title;
  if (typeof actionKey === 'string' && actionKey.trim()) {
    const sub =
      typeof subject === 'string' && subject.trim() ? ` ${subject.trim()}` : '';
    return `${actionKey.trim()}${sub}`;
  }
  if (typeof subject === 'string' && subject.trim()) {
    return `查询 ${subject.trim()}`;
  }
  return content?.displayName || content?.toolName || '工具';
}

/** 已完成计数，例如 { done: 2, total: 3 }。 */
export function todoProgress(todos: TodoItem[]): {
  done: number;
  total: number;
} {
  const total = todos.length;
  const done = todos.filter((t) => t.status === 'completed').length;
  return { done, total };
}

/**
 * 后端 ToolResultCompressor 会记录数组真实总数。
 * 返回压缩对象里的 `_count`；取不到则回退为已解析明细条数。
 */
export function todoTotal(content: ToolContent): number {
  const payload = parsePayload(content);
  const src = payload?.todos ?? payload?.items;
  if (src && typeof src === 'object' && typeof src._count === 'number') {
    return src._count;
  }
  return extractTodos(content).length;
}

/** 浏览器（Chrome）工具名特征。 */
const BROWSER_KEYWORD = /chrome|browse|浏览器/i;

/** 浏览器工具是否命中。 */
export function isBrowserTool(
  content: ToolContent,
  displayName?: string,
): boolean {
  const name = `${displayName || ''} ${content?.displayName || ''} ${content?.toolName || ''}`;
  return BROWSER_KEYWORD.test(name);
}

export interface BrowserResult {
  failed: boolean;
  errorCategory?: string;
  errorMessage?: string;
}

/**
 * 浏览器工具的真实成败，来自 artifact（而非后端 row.state，后者对浏览器恒为 SUCCEEDED）。
 * 后端把超时/失败编码成返回 JSON（ChromeResultVO：success:false + errorCategory）供 LLM 重试。
 * 这里以后端 timeline artifact 数据为准解析出失败标记。
 */
export function browserResult(
  content: ToolContent,
  displayName?: string,
): BrowserResult {
  if (!isBrowserTool(content, displayName)) return { failed: false };
  // 成败标记在 artifact（结果产物）里，不能走 args 优先的 parsePayload。
  const payload = safeParseJson(content?.artifact);
  if (payload && payload.success === false) {
    return {
      failed: true,
      errorCategory:
        typeof payload.errorCategory === 'string'
          ? payload.errorCategory
          : undefined,
      errorMessage:
        typeof payload.errorMessage === 'string'
          ? payload.errorMessage
          : undefined,
    };
  }
  return { failed: false };
}

/**
 * 工具卡友好渲染的纯函数判别/摘要逻辑。
 * 不依赖 React / antd，便于 jest 单测。
 *
 * 依据后端 AgentTimelineVO.content（tool 行）字段：
 *   displayName, status, args(JSON 字符串), artifact(JSON 字符串)
 * - todo 工具 args: { "todos": [{ "content","status","priority","skillId","stepId" }] }
 *   status ∈ pending|in_progress|completed|cancelled; priority ∈ high|medium|low
 *   skillId/stepId 仅在条目来自某技能步骤时存在（用于标识来源技能与步骤）
 * - doc 工具 args: { "action","title","content","documentId", ... }  action ∈ create|update|read|...
 */

export interface TodoItem {
  content: string;
  status?: string;
  priority?: string;
  /** 来源技能 id；非技能条目为 undefined/null。 */
  skillId?: number | null;
  /** 来源技能步骤标识，如 step1 / step1.1。 */
  stepId?: string | null;
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
  /** 后端 ToolMetaResolver 下发的标准化展示字段。 */
  meta?: Record<string, any>;
}

export type ToolFamily = 'todo' | 'doc' | 'other';

const TODO_KEYWORD = /待办|todo/i;
const DOC_KEYWORD = /文档|document|docwrite|docread/i;

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
    skillId: t?.skillId ?? null,
    stepId: t?.stepId ?? null,
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

/** 文档工具摘要：`create document <title>`；标题优先 meta/artifact，缺失回退 #documentId。 */
export function docSummary(content: ToolContent): string {
  const args = safeParseJson(content?.args) || {};
  const artifact = safeParseJson(content?.artifact) || {};
  const meta = content?.meta || {};
  const action =
    firstNonBlank(args.action, artifact.action, meta.action) || 'document';
  const title = docTitle(args, artifact, meta);
  const label = action === 'document' ? 'document' : `${action} document`;
  return title ? `${label} ${title}` : label;
}

/** 文档摘要里的标题：meta > artifact > args > #documentId。 */
function docTitle(args: any, artifact: any, meta: any): string {
  const title = [meta?.title, artifact?.title, args?.title].find(
    (value) => typeof value === 'string' && value.trim(),
  );
  if (title) {
    return title.trim();
  }
  const documentId =
    meta?.documentId ?? artifact?.documentId ?? args?.documentId;
  return documentId != null ? `#${documentId}` : '';
}

function firstNonBlank(...values: any[]): string {
  const value = values.find(
    (item) => typeof item === 'string' && item.trim() !== '',
  );
  return value ? value.trim() : '';
}

/** 通用工具摘要：基于 args 关键字段推导动作；无规则时回退显示工具名。 */
export function genericSummary(content: ToolContent): string {
  const payload = parsePayload(content);
  if (!payload) return content?.displayName || '工具';
  const actionKey =
    payload.action || payload.operation || payload.tool || payload.name;
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

export interface TextContainsView {
  /** true=命中，false=未命中，null=结果缺失/无法解析。 */
  hit: boolean | null;
  needle: string;
  mode: string;
  haystackChars: number | null;
  /** 工具返回的错误（例如 needle 为空）；非空时 hit 不可信。 */
  error: string | null;
}

/** 文本包含判断工具的可读摘要（builtin_11）。 */
export function textContainsView(content: ToolContent): TextContainsView {
  const args = safeParseJson(content?.args) || {};
  const artifact = safeParseJson(content?.artifact) || {};
  const meta = content?.meta || {};
  const needle =
    typeof meta.needle === 'string'
      ? meta.needle
      : typeof args.needle === 'string'
        ? args.needle
        : '';
  const haystack = typeof args.haystack === 'string' ? args.haystack : null;
  const rawMode =
    typeof meta.mode === 'string'
      ? meta.mode
      : typeof args.mode === 'string'
        ? args.mode.trim()
        : '';
  const hit =
    typeof meta.hit === 'boolean'
      ? meta.hit
      : typeof artifact.contains === 'boolean'
        ? artifact.contains
        : null;
  return {
    hit,
    needle,
    mode: rawMode ? rawMode.toUpperCase() : 'EXACT',
    haystackChars:
      typeof meta.haystackChars === 'number'
        ? meta.haystackChars
        : haystack != null
          ? haystack.length
          : null,
    error:
      typeof meta.error === 'string' && meta.error.trim()
        ? meta.error.trim()
        : typeof artifact.error === 'string' && artifact.error.trim()
          ? artifact.error.trim()
          : null,
  };
}

export interface WebPageView {
  url: string;
  title: string;
  statusCode: number | null;
  chars: number | null;
  error: string | null;
}

/** 网页读取 / 网页获取的摘要；正文只给长度，避免把整篇 markdown 塞进卡片。 */
export function webPageView(content: ToolContent): WebPageView {
  const args = safeParseJson(content?.args) || {};
  const artifact = safeParseJson(content?.artifact) || {};
  const meta = content?.meta || {};
  const body =
    typeof artifact.markdown === 'string'
      ? artifact.markdown
      : typeof artifact.content === 'string'
        ? artifact.content
        : '';
  return {
    url:
      typeof meta.url === 'string'
        ? meta.url
        : typeof args.url === 'string'
          ? args.url
          : '',
    title:
      typeof meta.title === 'string'
        ? meta.title
        : typeof artifact.title === 'string'
          ? artifact.title
          : '',
    statusCode:
      typeof meta.statusCode === 'number'
        ? meta.statusCode
        : typeof artifact.statusCode === 'number'
          ? artifact.statusCode
          : null,
    chars:
      typeof meta.chars === 'number' ? meta.chars : body ? body.length : null,
    error:
      typeof meta.error === 'string' && meta.error.trim()
        ? meta.error.trim()
        : typeof artifact.errorMessage === 'string' &&
            artifact.errorMessage.trim()
          ? artifact.errorMessage.trim()
          : typeof artifact.error === 'string' && artifact.error.trim()
            ? artifact.error.trim()
            : null,
  };
}

export interface SkillView {
  action: string;
  preview: string;
  total: number | null;
  names: string[];
  error: string | null;
}

/** 技能库 / 技能管理的摘要（两者共用 SkillWriteResultVO）。 */
export function skillView(content: ToolContent): SkillView {
  const args = safeParseJson(content?.args) || {};
  const artifact = safeParseJson(content?.artifact) || {};
  const meta = content?.meta || {};
  const skills = Array.isArray(artifact.skills)
    ? artifact.skills
    : Array.isArray(artifact.items)
      ? artifact.items
      : [];
  return {
    action:
      typeof meta.action === 'string' && meta.action.trim()
        ? meta.action.trim()
        : typeof args.action === 'string' && args.action.trim()
          ? args.action.trim()
          : typeof artifact.action === 'string' && artifact.action.trim()
            ? artifact.action.trim()
            : 'get',
    preview: typeof artifact.preview === 'string' ? artifact.preview : '',
    total:
      typeof meta.total === 'number'
        ? meta.total
        : typeof artifact.total === 'number'
          ? artifact.total
          : skills.length || null,
    names: Array.isArray(meta.names)
      ? meta.names.filter((n: any) => typeof n === 'string').slice(0, 3)
      : skills
          .map((s: any) => (typeof s?.name === 'string' ? s.name : ''))
          .filter(Boolean)
          .slice(0, 3),
    error:
      typeof meta.error === 'string' && meta.error.trim()
        ? meta.error.trim()
        : typeof artifact.error === 'string' && artifact.error.trim()
          ? artifact.error.trim()
          : null,
  };
}

export interface ReadToolResultView {
  callId: string;
  toolName: string;
  totalChars: number | null;
  offset: number | null;
  nextOffset: number | null;
  truncated: boolean;
  error: string | null;
}

/** read_tool_result：只展示回读范围，不展示正文。 */
export function readToolResultView(content: ToolContent): ReadToolResultView {
  const args = safeParseJson(content?.args) || {};
  const artifact = safeParseJson(content?.artifact) || {};
  const meta = content?.meta || {};
  const num = (key: string, fallback = null) =>
    typeof meta[key] === 'number' ? meta[key] : fallback;
  return {
    callId:
      typeof meta.callId === 'string'
        ? meta.callId
        : typeof artifact.callId === 'string'
          ? artifact.callId
          : typeof args.callId === 'string'
            ? args.callId
            : '',
    toolName:
      typeof meta.toolName === 'string'
        ? meta.toolName
        : typeof artifact.toolName === 'string'
          ? artifact.toolName
          : '',
    totalChars:
      num('totalChars') ??
      (typeof artifact.totalChars === 'number' ? artifact.totalChars : null),
    offset:
      num('offset') ??
      (typeof artifact.offset === 'number' ? artifact.offset : null),
    nextOffset:
      num('nextOffset') ??
      (typeof artifact.nextOffset === 'number' ? artifact.nextOffset : null),
    truncated: meta.truncated === true || artifact.truncated === true,
    error:
      typeof meta.error === 'string' && meta.error.trim()
        ? meta.error.trim()
        : typeof artifact.error === 'string' && artifact.error.trim()
          ? artifact.error.trim()
          : null,
  };
}

export interface ProgressView {
  summary: string;
  nextAction: string;
  percent: number | null;
}

/** progress 工具：summary / nextAction / percent。 */
export function progressView(content: ToolContent): ProgressView {
  const args = safeParseJson(content?.args) || {};
  const artifact = safeParseJson(content?.artifact) || {};
  const meta = content?.meta || {};
  const rawPercent =
    typeof meta.percent === 'number'
      ? meta.percent
      : typeof args.percent === 'number'
        ? args.percent
        : undefined;
  return {
    summary:
      typeof meta.summary === 'string' && meta.summary.trim()
        ? meta.summary.trim()
        : typeof args.summary === 'string' && args.summary.trim()
          ? args.summary.trim()
          : typeof artifact.summary === 'string'
            ? artifact.summary
            : '',
    nextAction:
      typeof meta.nextAction === 'string'
        ? meta.nextAction.trim()
        : typeof args.nextAction === 'string'
          ? args.nextAction.trim()
          : '',
    percent:
      rawPercent != null
        ? Math.max(0, Math.min(100, Math.round(rawPercent)))
        : null,
  };
}

export interface CliView {
  exitCode: number | null;
  stdout: string;
  stderr: string;
  firstLine: string;
  error: string | null;
}

/** CLI 工具：显示退出码和输出首行，完整输出进原始数据。 */
export function cliView(content: ToolContent): CliView {
  const artifact = safeParseJson(content?.artifact) || {};
  const meta = content?.meta || {};
  return {
    exitCode:
      typeof meta.exitCode === 'number'
        ? meta.exitCode
        : typeof artifact.exitCode === 'number'
          ? artifact.exitCode
          : null,
    stdout: typeof artifact.stdout === 'string' ? artifact.stdout : '',
    stderr: typeof artifact.stderr === 'string' ? artifact.stderr : '',
    firstLine: typeof meta.firstLine === 'string' ? meta.firstLine : '',
    error:
      typeof meta.error === 'string' && meta.error.trim()
        ? meta.error.trim()
        : typeof artifact.error === 'string' && artifact.error.trim()
          ? artifact.error.trim()
          : null,
  };
}

/** 中文文档动作摘要；保留 docSummary 的英文行为给测试/英文界面。 */
export function docSummaryZh(content: ToolContent): string {
  const args = safeParseJson(content?.args) || {};
  const artifact = safeParseJson(content?.artifact) || {};
  const meta = content?.meta || {};
  const action = firstNonBlank(args.action, artifact.action, meta.action) || '';
  const title = docTitle(args, artifact, meta);
  const actionZh: Record<string, string> = {
    create: '创建文档',
    update: '更新文档',
    read: '读取文档',
    delete: '删除文档',
    list: '文档列表',
    search: '搜索文档',
  };
  const label = actionZh[action] || action || '文档操作';
  return title ? `${label} ${title}` : label;
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
  method?: string;
  resultType?: string;
  warning?: string;
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
  const meta = content?.meta || {};
  const text = (v: any) =>
    typeof v === 'string' && v.trim() ? v.trim() : undefined;
  return {
    failed: meta.failed === true || payload?.success === false,
    errorCategory: text(meta.errorCategory) || text(payload?.errorCategory),
    errorMessage: text(meta.error) || text(payload?.errorMessage),
    method: text(meta.method) || text(payload?.method),
    resultType: text(meta.resultType) || text(payload?.resultType),
    warning: text(meta.warning) || text(payload?.warning),
  };
}

export interface SearchResultView {
  title: string;
  url: string;
  host: string;
  summary: string;
}

const SEARCH_ARRAY_KEYS = [
  'results',
  'items',
  'data',
  'searchResults',
  'entries',
  'content',
];
const SEARCH_TITLE_KEYS = ['title', 'name', 'headline'];
const SEARCH_URL_KEYS = ['url', 'link', 'href'];
const SEARCH_SUMMARY_KEYS = [
  'snippet',
  'description',
  'summary',
  'abstract',
  'content',
  'text',
];

function firstString(value: any, keys: string[]): string {
  if (!value || typeof value !== 'object') return '';
  for (const key of keys) {
    if (typeof value[key] === 'string' && value[key].trim()) {
      return value[key].trim();
    }
  }
  return '';
}

function parseJsonText(value: any): any {
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

function isTextPart(value: any): boolean {
  return (
    !!value &&
    typeof value === 'object' &&
    typeof value.text === 'string' &&
    value.url == null &&
    value.link == null &&
    value.href == null &&
    value.title == null &&
    value.name == null
  );
}

/** 递归展开 MCP 的 content[].text（部分服务端把搜索结果再包一层 JSON 字符串）。 */
function findResultArray(payload: any, depth = 0): any[] {
  if (depth > 4 || payload == null) return [];
  const value = parseJsonText(payload);
  if (Array.isArray(value)) {
    if (value.length > 0 && value.every(isTextPart)) {
      for (const part of value) {
        const found = findResultArray(part.text, depth + 1);
        if (found.length) return found;
      }
      return [];
    }
    return value;
  }
  if (typeof value !== 'object') return [];
  for (const key of SEARCH_ARRAY_KEYS) {
    if (value[key] == null) continue;
    const found = findResultArray(value[key], depth + 1);
    if (found.length) return found;
  }
  return [];
}

/** MCP 结果正文：优先取 content[].text，避免预览弹窗里全是 RPC 包装 JSON。 */
export function toolResultText(content: ToolContent): string {
  const payload = safeParseJson(content?.artifact);
  if (payload && typeof payload === 'object') {
    const parts = Array.isArray(payload.content) ? payload.content : [];
    const texts = parts
      .filter(isTextPart)
      .map((part: any) => part.text)
      .filter(Boolean);
    if (texts.length) return texts.join('\n\n');
  }
  return typeof content?.artifact === 'string' ? content.artifact : '';
}

/** 工具调用理由：后端 meta 优先，旧数据回退解析 args._reason。 */
export function toolCallReason(content: ToolContent): string {
  const meta = content?.meta || {};
  if (typeof meta.reason === 'string' && meta.reason.trim()) {
    return meta.reason.replace(/\s+/g, ' ').trim();
  }
  const args = safeParseJson(content?.args);
  const reason = args?._reason;
  return typeof reason === 'string' ? reason.replace(/\s+/g, ' ').trim() : '';
}

/**
 * MCP 搜索类工具的数组结果：提取标题 + 域名 + 摘要。
 * 只在调用方（MCP 工具卡）使用，避免把技能/待办等数组误当成搜索结果。
 */
export function searchResultView(content: ToolContent): SearchResultView[] {
  const payload = safeParseJson(content?.artifact);
  const list = findResultArray(payload);
  const results: SearchResultView[] = [];
  for (const item of list) {
    if (!item || typeof item !== 'object') continue;
    const url = firstString(item, SEARCH_URL_KEYS);
    const title = firstString(item, SEARCH_TITLE_KEYS);
    if (!url && !title) continue;
    let host = '';
    try {
      host = url ? new URL(url).host : '';
    } catch {
      host = '';
    }
    const summary = firstString(item, SEARCH_SUMMARY_KEYS)
      .replace(/\s+/g, ' ')
      .slice(0, 180);
    results.push({ title: title || host || url, url, host, summary });
    if (results.length >= 5) break;
  }
  return results;
}

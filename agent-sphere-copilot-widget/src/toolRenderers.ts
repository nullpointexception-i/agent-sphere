/**
 * Pure discrimination/summary logic for friendly tool card rendering (ported from the UmiJS main site).
 * No React / antd dependency, so it is easy to unit test with vitest.
 *
 * Based on the backend AgentTimelineVO.content (tool rows) fields:
 *   displayName, status, args(JSON string), artifact(JSON string)
 * - todo tool args: { "todos": [{ "content","status","priority" }] }
 *   status ∈ pending|in_progress|completed|cancelled; priority ∈ high|medium|low
 * - doc tool args: { "action","title","content","documentId", ... }  action ∈ create|update|read|...
 */

export interface TodoItem {
  content: string;
  status?: string;
  priority?: string;
  /** Source skill id; undefined/null for non-skill entries. */
  skillId?: number | null;
  /** Source skill step identifier, such as step1 / step1.1. */
  stepId?: string | null;
}

export interface ToolContent {
  displayName?: string;
  /** Tool name (backend field name) */
  toolName?: string;
  /** Tool status/result status */
  status?: string;
  /** Call arguments, JSON string */
  args?: string;
  /** Execution artifact, JSON string */
  artifact?: string;
  /** Normalized display fields delivered by the backend ToolMetaResolver. */
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

/** Parse the raw objects out of content's args / artifact (already JSON.parse'd). */
function parsePayload(content: ToolContent): any {
  const args = safeParseJson(content?.args);
  if (args) return args;
  return safeParseJson(content?.artifact);
}

/**
 * The backend ToolResultCompressor compresses JSON arrays into an object
 * { "_count": N, "_showing": N, "items": [first N items] } (empty array -> items:[]).
 * This function restores the compressed array form to a real array.
 */
function asArray(v: any): any[] | null {
  if (Array.isArray(v)) return v;
  if (v && typeof v === 'object' && Array.isArray(v.items)) return v.items;
  return null;
}

/** Extract the todos list (args first, then artifact). Compatible with the compressed array form. */
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
          : 'Untitled task',
    status: t?.status,
    priority: t?.priority,
    skillId: t?.skillId ?? null,
    stepId: t?.stepId ?? null,
  }));
}

/**
 * Discriminate the tool family:
 * - args contain a todos array -> todo
 * - args contain action + (title | documentId | content) -> doc (or displayName suggests a document)
 * - everything else -> other
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

/** Document tool summary: `create document <title>`; title prefers meta/artifact and falls back to #documentId. */
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

/** Title in the document summary: meta > artifact > args > #documentId. */
function docTitle(args: any, artifact: any, meta: any): string {
  const title = [meta?.title, artifact?.title, args?.title].find(
    (value) => typeof value === 'string' && value.trim(),
  );
  if (title) {
    return title.trim();
  }
  const documentId = meta?.documentId ?? artifact?.documentId ?? args?.documentId;
  return documentId != null ? `#${documentId}` : '';
}

function firstNonBlank(...values: any[]): string {
  const value = values.find(
    (item) => typeof item === 'string' && item.trim() !== '',
  );
  return value ? value.trim() : '';
}

/** Generic tool summary: derive the action from key args fields; fall back to the tool name when no rule matches. */
export function genericSummary(content: ToolContent): string {
  const payload = parsePayload(content);
  if (!payload) return content?.displayName || 'Tool';
  const actionKey =
    payload.action ||
    payload.operation ||
    payload.tool ||
    payload.name;
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
    return `Search ${subject.trim()}`;
  }
  return content?.displayName || content?.toolName || 'Tool';
}

export interface TextContainsView {
  /** true=hit, false=miss, null=result missing/unparsable. */
  hit: boolean | null;
  needle: string;
  mode: string;
  haystackChars: number | null;
  /** Error returned by the tool (for example an empty needle); hit is unreliable when non-empty. */
  error: string | null;
}

/** Readable summary of the text-contains tool (builtin_11). */
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
  return {
    hit:
      typeof meta.hit === 'boolean'
        ? meta.hit
        : typeof artifact.contains === 'boolean'
          ? artifact.contains
          : null,
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

/** Summary for web page read / web fetch; the body is shown only as a length, so a whole markdown page is not crammed into the card. */
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
      typeof meta.chars === 'number'
        ? meta.chars
        : body
          ? body.length
          : null,
    error:
      typeof meta.error === 'string' && meta.error.trim()
        ? meta.error.trim()
        : typeof artifact.errorMessage === 'string' && artifact.errorMessage.trim()
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

/** Summary for skill hub / skill management (both share SkillWriteResultVO). */
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

/** read_tool_result: show only the read-back range, not the body. */
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

/** progress tool: summary / nextAction / percent. */
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

/** CLI tool: show the exit code and the first line of output; full output goes into the raw data. */
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

/**
 * English document action summary for the doc card.
 *
 * @deprecated Kept as a thin alias of {@link docSummary} for existing callers; it no longer localizes to Chinese.
 */
export function docSummaryZh(content: ToolContent): string {
  return docSummary(content);
}

/** Completed count, for example { done: 2, total: 3 }. */
export function todoProgress(todos: TodoItem[]): {
  done: number;
  total: number;
} {
  const total = todos.length;
  const done = todos.filter((t) => t.status === 'completed').length;
  return { done, total };
}

/**
 * The backend ToolResultCompressor records the real total of an array.
 * Returns `_count` from the compressed object; falls back to the number of parsed detail entries when unavailable.
 */
export function todoTotal(content: ToolContent): number {
  const payload = parsePayload(content);
  const src = payload?.todos ?? payload?.items;
  if (src && typeof src === 'object' && typeof src._count === 'number') {
    return src._count;
  }
  return extractTodos(content).length;
}

/** Browser (Chrome) tool name characteristics. */
const BROWSER_KEYWORD = /chrome|browse|浏览器/i;

/** Whether a browser tool is matched. */
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
 * The real success/failure of browser tools comes from artifact (not the backend row.state, which is always SUCCEEDED for browsers).
 * The backend encodes timeouts/failures into the returned JSON (ChromeResultVO: success:false + errorCategory) for the LLM to retry.
 * Here the failure flag is parsed from the backend timeline artifact data.
 */
export function browserResult(
  content: ToolContent,
  displayName?: string,
): BrowserResult {
  if (!isBrowserTool(content, displayName)) return { failed: false };
  // The success/failure flag lives in artifact (the result payload), so it cannot go through the args-first parsePayload.
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

const SEARCH_ARRAY_KEYS = ['results', 'items', 'data', 'searchResults', 'entries', 'content'];
const SEARCH_TITLE_KEYS = ['title', 'name', 'headline'];
const SEARCH_URL_KEYS = ['url', 'link', 'href'];
const SEARCH_SUMMARY_KEYS = ['snippet', 'description', 'summary', 'abstract', 'content', 'text'];

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

/** Recursively unwrap MCP content[].text (some servers wrap search results in another JSON string). */
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

/** MCP result body: prefer content[].text, so the preview modal is not full of RPC wrapper JSON. */
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

/** Tool call reason: backend meta first, falling back to parsing args._reason for old data. */
export function toolCallReason(content: ToolContent): string {
  const meta = content?.meta || {};
  if (typeof meta.reason === 'string' && meta.reason.trim()) {
    return meta.reason.replace(/\s+/g, ' ').trim();
  }
  const args = safeParseJson(content?.args);
  const reason = args?._reason;
  return typeof reason === 'string' ? reason.replace(/\s+/g, ' ').trim() : '';
}

/** Array results of MCP search tools: title + domain + summary. */
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

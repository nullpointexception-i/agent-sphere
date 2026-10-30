import { clearUser, getToken } from './auth';
import { loadCachedObjectUrl } from './fileCache';
import {
  CLIENT_SURFACE_HEADER,
  CLIENT_SURFACE_WIDGET,
  SSO_AUTHORIZE_PATH,
  SSO_EXCHANGE_PATH,
  type WidgetConfig,
} from './config';
import type {
  InstancePageVO,
  SessionTimelinePageVO,
  SessionVO,
  SsoIdentityVO,
  SubAgentTimelineItemVO,
  UsageData,
  UserVO,
} from './types';

export interface ErrorBody {
  errorCode?: string;
  errorMessage?: string;
  userTip?: string;
  message?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ErrorBody;

  constructor(status: number, body: ErrorBody) {
    super(body.userTip || body.errorMessage || body.message || `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

function buildUrl(base: string, path: string, params?: Record<string, string>): string {
  const url = new URL(`${base}${path}`, window.location.origin);
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      url.searchParams.set(key, value);
    }
  }
  return url.toString();
}

interface RequestOptions extends Omit<RequestInit, 'params'> {
  params?: Record<string, string>;
}

async function request<T>(
  base: string,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { params, ...init } = options;
  const token = getToken();
  const headers = new Headers(init.headers);
  if (!headers.has('Content-Type') && init.body != null) {
    headers.set('Content-Type', 'application/json');
  }
  // 声明调用来源：服务端按实例的 allow_widget_session 开关校验建会话/发消息。
  // 缺省（不带头）会被当作主站，那样关掉了 widget 的实例在这里仍会被放行。
  headers.set(CLIENT_SURFACE_HEADER, CLIENT_SURFACE_WIDGET);
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const response = await fetch(buildUrl(base, path, params), { ...init, headers });
  if (response.status === 401) {
    clearUser();
    window.dispatchEvent(new CustomEvent('agent-sphere:logout'));
  }
  if (!response.ok) {
    let body: ErrorBody = {};
    try {
      body = (await response.json()) as ErrorBody;
    } catch {
      body = { message: response.statusText };
    }
    throw new ApiError(response.status, body);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** 拉取附件字节（Bearer 鉴权，支持中止）。 */
async function fetchFileBlob(
  base: string,
  fileKey: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const token = getToken();
  const headers = new Headers();
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const response = await fetch(buildUrl(base, `/files/${fileKey}`), {
    headers,
    signal,
  });
  if (!response.ok) {
    throw new ApiError(response.status, { message: response.statusText });
  }
  return response.blob();
}

/**
 * 按 fileKey 回读附件字节（聊天历史图片回显），转 objectURL 交给 <img> 渲染。
 * 走 module 级缓存 + 并发去重（见 fileCache），避免重复下载与 objectURL 泄漏。
 */
export function loadFileObjectUrl(
  base: string,
  fileKey: string,
): Promise<string> {
  return loadCachedObjectUrl(
    `${base}::${fileKey}`,
    (_key, signal) => fetchFileBlob(base, fileKey, signal),
    fileKey,
  );
}

/** 上传聊天图片附件（FormData，Bearer 鉴权），返回 fileKey 供随消息发送。 */
export async function uploadFile(
  base: string,
  file: File,
): Promise<{ fileKey: string; contentType: string; sizeBytes: number }> {
  const token = getToken();
  const headers = new Headers();
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  // 勿设 Content-Type，浏览器自动带 multipart boundary
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(buildUrl(base, '/files/upload'), {
    method: 'POST',
    headers,
    body: formData,
  });
  if (!response.ok) {
    let body: ErrorBody = {};
    try {
      body = (await response.json()) as ErrorBody;
    } catch {
      body = { message: response.statusText };
    }
    throw new ApiError(response.status, body);
  }
  return (await response.json()) as { fileKey: string; contentType: string; sizeBytes: number };
}

export function ssoAuthorize(
  base: string,
  provider: string,
  redirectUri: string,
  prompt?: string,
): Promise<{ authorizeUrl: string }> {
  const params: Record<string, string> = { provider, redirect_uri: redirectUri };
  if (prompt) {
    params.prompt = prompt;
  }
  return request(base, SSO_AUTHORIZE_PATH, { params });
}

export function ssoExchange(base: string, otc: string): Promise<UserVO> {
  return request(base, SSO_EXCHANGE_PATH, {
    method: 'POST',
    body: JSON.stringify({ otc }),
  });
}

export function ssoMe(base: string): Promise<SsoIdentityVO> {
  return request<SsoIdentityVO>(base, '/sso/me');
}

export function listInstancesPage(
  base: string,
  page = 1,
  size = 20,
): Promise<InstancePageVO> {
  return request(base, '/instance/instances', {
    params: { page: String(page), size: String(size) },
  });
}

export function listSessions(
  base: string,
  offset = 0,
  limit = 50,
  keyword?: string,
): Promise<SessionVO[]> {
  const params: Record<string, string> = { offset: String(offset), limit: String(limit) };
  if (keyword) {
    params.keyword = keyword;
  }
  return request(base, '/instance/sessions', { params });
}

export function createSession(
  base: string,
  agentInstanceId: number,
  title: string,
): Promise<SessionVO> {
  return request(base, '/instance/sessions', {
    method: 'POST',
    body: JSON.stringify({ agentInstanceId, title }),
  });
}

export function renameSession(base: string, id: number, title: string): Promise<SessionVO> {
  return request(base, `/instance/sessions/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ title }),
  });
}

export function closeSession(base: string, id: number): Promise<void> {
  return request(base, `/instance/sessions/${id}`, { method: 'DELETE' });
}

/** 子 Agent 时间线（interactions + tool_calls 按时序）。 */
export function subAgentTimeline(base: string, subAgentRunId: number): Promise<SubAgentTimelineItemVO[]> {
  return request(base, `/instance/sub-agent-runs/${subAgentRunId}/timeline`);
}

/** 公开配置读取（免鉴权）：当前支持 plugin.download-url（插件安装包下载地址）。 */
export function publicConfig(base: string, keys: string[]): Promise<Record<string, string>> {
  return request(base, '/system/config/public', {
    params: { keys: keys.join(',') },
  });
}

/** session 级停止：停止该 session 当前运行中的 run（不依赖 runId）。 */
export function stopSession(base: string, sessionId: number): Promise<void> {
  return request(base, `/runtime/${sessionId}/stop`, { method: 'POST' });
}

/** 会话级 token 用量（吸底条）。 */
export function sessionUsage(base: string, sessionId: number): Promise<UsageData> {
  return request<UsageData>(base, `/instance/sessions/${sessionId}/usage`);
}

export interface TimelineQuery {
  beforeSeq?: number;
  afterSeq?: number;
  limit?: number;
}

/** 会话 timeline 分页（typed log：user/assistant/tool/clarification/subagent/run_status）。 */
export function getTimeline(
  base: string,
  sessionId: number,
  query: TimelineQuery = {},
): Promise<SessionTimelinePageVO> {
  const params: Record<string, string> = {};
  if (query.beforeSeq != null) params.beforeSeq = String(query.beforeSeq);
  if (query.afterSeq != null) params.afterSeq = String(query.afterSeq);
  params.limit = String(query.limit ?? 10);
  return request(base, `/instance/sessions/${sessionId}/timeline`, { params });
}

/** 发送消息（REST 方式，SSE 通道负责后续打字机/补行）。 */
export function sendMessage(
  base: string,
  sessionId: number,
  message: string,
  attachmentKeys?: string[],
): Promise<{ runId: number; status: string }> {
  return request(base, `/runtime/${sessionId}/chat`, {
    method: 'POST',
    body: JSON.stringify({ message, attachmentKeys }),
  });
}

export interface ToolApprovalVO {
  approvalId: string;
  sessionId: number;
  runId: number;
  toolCallsJson: string;
  status: string;
}

export function pendingToolApproval(base: string, sessionId: number): Promise<ToolApprovalVO | null> {
  return request<ToolApprovalVO | null>(base, `/runtime/${sessionId}/tool-approvals/pending`);
}

export function resolveToolApproval(
  base: string,
  sessionId: number,
  runId: number,
  approvalId: string,
  decisions: Array<{ callId: string; decision: string }>,
): Promise<void> {
  return request(base, `/runtime/${sessionId}/run/${runId}/tool-approvals/${approvalId}`, {
    method: 'POST',
    body: JSON.stringify({ decisions }),
  });
}

export interface ClarificationVO {
  clarificationId: string;
  sessionId: number;
  runId: number;
  title?: string;
  type?: string;
  multiple?: boolean;
  options?: string;
  description?: string;
  status?: string;
}

export function pendingClarifications(
  base: string,
  sessionId: number,
): Promise<ClarificationVO[]> {
  return request<ClarificationVO[]>(base, `/runtime/${sessionId}/clarifications/pending`);
}

export function resolveClarifications(
  base: string,
  sessionId: number,
  runId: number,
  answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
): Promise<unknown> {
  return request(base, `/runtime/${sessionId}/run/${runId}/clarifications/resolve`, {
    method: 'POST',
    body: JSON.stringify({ answers }),
  });
}

/** 澄清应答。 */
export function clarify(
  base: string,
  sessionId: number,
  runId: number,
  response: string,
  clarificationId?: string,
): Promise<unknown> {
  return request(base, `/runtime/${sessionId}/run/${runId}/clarify`, {
    method: 'POST',
    body: JSON.stringify({ response, clarificationId }),
  });
}

export interface ApiClient {
  ssoAuthorize: (provider: string, redirectUri: string, prompt?: string) => Promise<string>;
  ssoExchange: (otc: string) => Promise<UserVO>;
  ssoMe: () => Promise<SsoIdentityVO>;
  listSessions: (offset?: number, limit?: number, keyword?: string) => Promise<SessionVO[]>;
  createSession: (agentInstanceId: number, title: string) => Promise<SessionVO>;
  renameSession: (id: number, title: string) => Promise<SessionVO>;
  closeSession: (id: number) => Promise<void>;
  publicConfig: (keys: string[]) => Promise<Record<string, string>>;
  stopSession: (sessionId: number) => Promise<void>;
  sessionUsage: (sessionId: number) => Promise<UsageData>;
  listInstancesPage: (page?: number, size?: number) => Promise<InstancePageVO>;
  subAgentTimeline: (subAgentRunId: number) => Promise<SubAgentTimelineItemVO[]>;
  getTimeline: (sessionId: number, query?: TimelineQuery) => Promise<SessionTimelinePageVO>;
  loadFile: (fileKey: string) => Promise<string>;
  uploadFile: (file: File) => Promise<{ fileKey: string; contentType: string; sizeBytes: number }>;
  sendMessage: (
    sessionId: number,
    message: string,
    attachmentKeys?: string[],
  ) => Promise<{ runId: number; status: string }>;
  clarify: (
    sessionId: number,
    runId: number,
    response: string,
    clarificationId?: string,
  ) => Promise<unknown>;
  pendingClarifications: (sessionId: number) => Promise<ClarificationVO[]>;
  resolveClarifications: (
    sessionId: number,
    runId: number,
    answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
  ) => Promise<unknown>;
  pendingToolApproval: (sessionId: number) => Promise<ToolApprovalVO | null>;
  resolveToolApproval: (
    sessionId: number,
    runId: number,
    approvalId: string,
    decisions: Array<{ callId: string; decision: string }>,
  ) => Promise<void>;
}

export function createApi(config: WidgetConfig): ApiClient {
  const base = config.apiBase ?? '/api/v1';
  return {
    ssoAuthorize: (provider, redirectUri, prompt) =>
      ssoAuthorize(base, provider, redirectUri, prompt).then((v) => v.authorizeUrl),
    ssoExchange: (otc) => ssoExchange(base, otc),
    ssoMe: () => ssoMe(base),
    listSessions: (offset, limit, keyword) => listSessions(base, offset, limit, keyword),
    createSession: (agentInstanceId, title) => createSession(base, agentInstanceId, title),
    renameSession: (id, title) => renameSession(base, id, title),
    closeSession: (id) => closeSession(base, id),
    publicConfig: (keys) => publicConfig(base, keys),
    stopSession: (sessionId) => stopSession(base, sessionId),
    sessionUsage: (sessionId) => sessionUsage(base, sessionId),
    listInstancesPage: (page, size) => listInstancesPage(base, page, size),
    subAgentTimeline: (subAgentRunId) => subAgentTimeline(base, subAgentRunId),
    getTimeline: (sessionId, query) => getTimeline(base, sessionId, query),
    loadFile: (fileKey) => loadFileObjectUrl(base, fileKey),
    uploadFile: (file) => uploadFile(base, file),
    sendMessage: (sessionId, message, attachmentKeys) =>
      sendMessage(base, sessionId, message, attachmentKeys),
    clarify: (sessionId, runId, response, clarificationId) =>
      clarify(base, sessionId, runId, response, clarificationId),
    pendingClarifications: (sessionId) => pendingClarifications(base, sessionId),
    resolveClarifications: (sessionId, runId, answers) =>
      resolveClarifications(base, sessionId, runId, answers),
    pendingToolApproval: (sessionId) => pendingToolApproval(base, sessionId),
    resolveToolApproval: (sessionId, runId, approvalId, decisions) =>
      resolveToolApproval(base, sessionId, runId, approvalId, decisions),
  };
}

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
  // Declare the call surface: the server validates session creation/sends against the instance's allow_widget_session switch.
  // Without the header the call is treated as the main site, so instances with the widget disabled here would still be allowed.
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

/** Fetch attachment bytes (Bearer auth, abortable). */
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
 * Read attachment bytes back by fileKey (chat history image replay) and turn them into an objectURL for <img> rendering.
 * Uses a module-level cache plus in-flight deduplication (see fileCache) to avoid duplicate downloads and leaked objectURLs.
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

/** Upload a chat image attachment (FormData, Bearer auth) and return the fileKey to send with the message. */
export async function uploadFile(
  base: string,
  file: File,
): Promise<{ fileKey: string; contentType: string; sizeBytes: number }> {
  const token = getToken();
  const headers = new Headers();
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  // Do not set Content-Type; the browser adds the multipart boundary automatically
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

/** Sub-agent timeline (interactions + tool_calls in chronological order). */
export function subAgentTimeline(base: string, subAgentRunId: number): Promise<SubAgentTimelineItemVO[]> {
  return request(base, `/instance/sub-agent-runs/${subAgentRunId}/timeline`);
}

/** Public configuration read (no auth): currently supports plugin.download-url (plugin installer download URL). */
export function publicConfig(base: string, keys: string[]): Promise<Record<string, string>> {
  return request(base, '/system/config/public', {
    params: { keys: keys.join(',') },
  });
}

/** Session-level stop: stops the run currently executing in this session (does not need runId). */
export function stopSession(base: string, sessionId: number): Promise<void> {
  return request(base, `/runtime/${sessionId}/stop`, { method: 'POST' });
}

/** Session-level token usage (sticky footer bar). */
export function sessionUsage(base: string, sessionId: number): Promise<UsageData> {
  return request<UsageData>(base, `/instance/sessions/${sessionId}/usage`);
}

export interface TimelineQuery {
  beforeSeq?: number;
  afterSeq?: number;
  limit?: number;
}

/** Session timeline pagination (typed log: user/assistant/tool/clarification/subagent/run_status). */
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

/** Session runtime state (aggregated from source tables): the single authority for the send lock / pending checks. */
export function getRuntimeState(
  base: string,
  sessionId: number,
): Promise<RuntimeStateVO> {
  return request(base, `/runtime/${sessionId}/state`);
}

/** Send a message (REST); the SSE channel handles the subsequent typewriter output and catch-up. */
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

/** Clarification answer. */
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

export interface RuntimeStateVO {
  sessionId: number;
  runId?: number | null;
  runStatus?: string | null;
  loopCount?: number | null;
  loopLimit?: number | null;
  runMode?: string | null;
  pendingClarifications: number;
  pendingToolApprovals: number;
  pendingQueue: number;
  canSend: boolean;
  /** Suspension type: WAIT / LLM_RETRY. */
  waitKind?: string | null;
  /** LLM retry attempt and total attempts (k/N). */
  retryAttempt?: number | null;
  retryMax?: number | null;
  /** Absolute time of the next retry (epoch ms). */
  nextRetryAt?: number | null;
  /** Milliseconds until the next retry (computed by the server, counted down in the UI). */
  retryRemainingMs?: number | null;
  blockReasons: string[];
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
  getRuntimeState: (sessionId: number) => Promise<RuntimeStateVO>;
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
    getRuntimeState: (sessionId) => getRuntimeState(base, sessionId),
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

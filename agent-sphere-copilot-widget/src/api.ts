import { getToken } from './auth';
import {
  SSO_AUTHORIZE_PATH,
  SSO_EXCHANGE_PATH,
  type WidgetConfig,
} from './config';
import type { InstanceVO, SessionVO, UserVO } from './types';

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
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const response = await fetch(buildUrl(base, path, params), { ...init, headers });
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

export function listInstances(base: string): Promise<InstanceVO[]> {
  return request(base, '/instance/instances/all');
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

export interface ApiClient {
  ssoAuthorize: (provider: string, redirectUri: string, prompt?: string) => Promise<string>;
  ssoExchange: (otc: string) => Promise<UserVO>;
  listInstances: () => Promise<InstanceVO[]>;
  listSessions: () => Promise<SessionVO[]>;
  createSession: (agentInstanceId: number, title: string) => Promise<SessionVO>;
  renameSession: (id: number, title: string) => Promise<SessionVO>;
  closeSession: (id: number) => Promise<void>;
}

export function createApi(config: WidgetConfig): ApiClient {
  const base = config.apiBase ?? '/api/v1';
  return {
    ssoAuthorize: (provider, redirectUri, prompt) =>
      ssoAuthorize(base, provider, redirectUri, prompt).then((v) => v.authorizeUrl),
    ssoExchange: (otc) => ssoExchange(base, otc),
    listInstances: () => listInstances(base),
    listSessions: () => listSessions(base),
    createSession: (agentInstanceId, title) => createSession(base, agentInstanceId, title),
    renameSession: (id, title) => renameSession(base, id, title),
    closeSession: (id) => closeSession(base, id),
  };
}

import { request } from '@umijs/max';
import { API_BASE } from '@/constants/api';

const BASE = API_BASE;

export interface LocalRuntime {
  id: number;
  name: string;
  runtimeKind: 'OPENCODE' | 'CODEX';
  status: string;
  online: boolean;
  appVersion?: string;
  runtimeVersion?: string;
  os?: string;
  arch?: string;
  tokenPrefix?: string;
  lastSeenAt?: string;
  createdAt?: string;
}

export interface IssuedLocalRuntimeToken {
  id: number;
  name: string;
  runtimeKind: string;
  token: string;
  tokenPrefix: string;
}

export const localRuntimeApi = {
  // These calls are used for device cleanup/recovery; their 404s are expected and
  // must not raise the global error toast.
  list: () =>
    request<LocalRuntime[]>(`${BASE}/local-runtimes`, {
      skipErrorHandler: true,
    }),
  create: (data: {
    name: string;
    runtimeKind: 'OPENCODE' | 'CODEX';
    deviceKey?: string;
    os?: string;
    arch?: string;
  }) =>
    request<IssuedLocalRuntimeToken>(`${BASE}/local-runtimes`, {
      method: 'POST',
      data,
    }),
  rotateToken: (id: number) =>
    request<IssuedLocalRuntimeToken>(`${BASE}/local-runtimes/${id}/token`, {
      method: 'POST',
    }),
  delete: (id: number) =>
    request<void>(`${BASE}/local-runtimes/${id}`, {
      method: 'DELETE',
      skipErrorHandler: true,
    }),
  update: (id: number, data: { name?: string; status?: string }) =>
    request<LocalRuntime>(`${BASE}/local-runtimes/${id}`, {
      method: 'PATCH',
      data,
    }),
};

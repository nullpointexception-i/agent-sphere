import { API_BASE } from './api';

/**
 * Full front-end API paths. Keep these centralized so the API prefix and auth routes have
 * one source of truth. Dynamic paths are functions.
 */
export const API_PATHS = {
  AUTH_LOGIN: `${API_BASE}/auth/login`,
  AUTH_LOGOUT: `${API_BASE}/auth/logout`,
  AUTH_LOGOUT_ALL: `${API_BASE}/auth/logout-all`,
  AUTH_ME: `${API_BASE}/auth/me`,
  AUTH_CHECK_USERNAME: `${API_BASE}/auth/check-username`,
  AUTH_REGISTER: `${API_BASE}/auth/register`,
  AUTH_PROFILE: `${API_BASE}/auth/profile`,
  AUTH_PASSWORD: `${API_BASE}/auth/password`,
  SSO_ME: `${API_BASE}/sso/me`,
  SSO_AUTHORIZE: `${API_BASE}/auth/sso/authorize`,
  SSO_EXCHANGE: `${API_BASE}/auth/sso/exchange`,
  TRACK_FRONTEND: `${API_BASE}/track/frontend`,
  IM_STREAM: `${API_BASE}/im/stream`,
  RUNTIME_STREAM: (sessionId: string | number) =>
    `${API_BASE}/runtime/${sessionId}/stream`,
  PUBLIC_SHARE: (token: string) => `${API_BASE}/public/shares/${token}`,
  DOCUMENT_SHARE: (token: string) =>
    `${API_BASE}/artifacts/documents/shared/${token}`,
} as const;

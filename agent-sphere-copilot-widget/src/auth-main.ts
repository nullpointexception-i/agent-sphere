/**
 * 轻量认证入口（与 chat 包分离的独立 IIFE，输出 agent-sphere-auth.js）。
 *
 * 供宿主页面（如 Bole）在**不加载 16MB chat 包**的情况下完成 AS 的 OIDC 静默 SSO：
 * 认证逻辑归属 AS（RP）自身，宿主只加载本脚本并调用 init；浏览器与 AS 的交互
 * 是 RP→IdP 的常规 OIDC 方向，Bole 仅作为 IdP（重定向）与宿主。
 *
 * 流程完成后把 token 写入 sessionStorage['agent-sphere-widget:agent-user']
 * （与 widget auth.ts 同 shape），chrome-extension 读取同一 key 即可建立 task 连接。
 */
import { ssoAuthorize, ssoExchange, ssoMe } from './api';
import { clearUser, setUser } from './auth';
import {
  SSO_QUERY_PARAM_ERROR,
  SSO_QUERY_PARAM_OTC,
  type WidgetConfig,
} from './config';
import type { UserVO } from './types';

const STORAGE_TRIED_KEY = 'agent-sphere-widget:auto-login-tried';
const STORAGE_PENDING_KEY = 'bole:as:auto-sso-pending';
const AUTH_RESULT_EVENT = 'agent-sphere:auth-result';

export interface AuthResultDetail {
  via: 'exchange' | 'error' | 'skip';
  pending?: boolean;
}

function readParam(name: string): string | null {
  return new URLSearchParams(window.location.search).get(name);
}

function stripParams(names: string[]): void {
  const params = new URLSearchParams(window.location.search);
  let changed = false;
  for (const name of names) {
    if (params.has(name)) {
      params.delete(name);
      changed = true;
    }
  }
  if (!changed) {
    return;
  }
  const qs = params.toString();
  const url = `${window.location.pathname}${qs ? `?${qs}` : ''}`;
  window.history.replaceState(null, '', url);
}

function dispatchResult(detail: AuthResultDetail): void {
  window.dispatchEvent(new CustomEvent<AuthResultDetail>(AUTH_RESULT_EVENT, { detail }));
}

function readFlag(key: string): boolean {
  try {
    return sessionStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

function setFlag(key: string, value: boolean): void {
  try {
    if (value) {
      sessionStorage.setItem(key, '1');
    } else {
      sessionStorage.removeItem(key);
    }
  } catch {
    // storage unavailable
  }
}

async function syncSsoIdentity(base: string, user: UserVO): Promise<UserVO> {
  try {
    const identity = await ssoMe(base);
    if (identity?.providerCode && identity?.subject) {
      return {
        ...user,
        ssoProviderCode: identity.providerCode,
        ssoSubject: identity.subject,
      };
    }
  } catch {
    // 非 SSO 或接口异常：保留原用户，身份展示回退
  }
  return user;
}

async function run(base: string, provider: string, autoLogin: boolean): Promise<void> {
  const otc = readParam(SSO_QUERY_PARAM_OTC);
  const errorParam = readParam(SSO_QUERY_PARAM_ERROR);
  const redirectUri = window.location.origin + window.location.pathname;

  if (otc) {
    const pending = readFlag(STORAGE_PENDING_KEY);
    setFlag(STORAGE_PENDING_KEY, false);
    try {
      const user = await ssoExchange(base, otc);
      if (user?.token) {
        setUser(user);
        const merged = await syncSsoIdentity(base, user);
        setUser(merged);
      }
      setFlag(STORAGE_TRIED_KEY, false);
      console.log('[AgentSphereAuth] exchange ok, token stored', { pending });
      dispatchResult({ via: 'exchange', pending });
    } catch (err) {
      console.warn('[AgentSphereAuth] exchange failed', err);
      dispatchResult({ via: 'error', pending });
    } finally {
      stripParams([SSO_QUERY_PARAM_OTC]);
    }
    return;
  }

  if (errorParam) {
    setFlag(STORAGE_TRIED_KEY, true);
    console.warn('[AgentSphereAuth] sso error param:', errorParam);
    stripParams([SSO_QUERY_PARAM_ERROR]);
    dispatchResult({ via: 'error' });
    return;
  }

  // 直接清空本地 token，兼容切换用户：每次 init 都重新静默登录当前 Bole 用户，
  // 旧 token 不会残留阻塞或跨用户泄漏
  clearUser();
  setFlag(STORAGE_TRIED_KEY, false);

  if (!autoLogin) {
    console.log('[AgentSphereAuth] autoLogin disabled, skip');
    dispatchResult({ via: 'skip' });
    return;
  }

  setFlag(STORAGE_TRIED_KEY, true);
  setFlag(STORAGE_PENDING_KEY, true);
  try {
    const { authorizeUrl } = await ssoAuthorize(base, provider, redirectUri, 'none');
    console.log('[AgentSphereAuth] probe redirect ->', authorizeUrl);
    window.location.assign(authorizeUrl);
  } catch (err) {
    setFlag(STORAGE_PENDING_KEY, false);
    console.warn('[AgentSphereAuth] authorize failed', err);
    dispatchResult({ via: 'error' });
  }
}

export interface AgentSphereAuthApi {
  init: (config?: WidgetConfig) => void;
  /** 清除会话与重试标记；宿主切换用户后调用，随后重新 init 触发新用户的静默 SSO。 */
  reset: () => void;
}

const api: AgentSphereAuthApi = {
  init: (config: WidgetConfig = {}) => {
    const base = config.apiBase ?? '/api/v1';
    const provider = config.provider ?? 'business';
    const autoLogin = config.autoLogin !== false;
    console.log('[AgentSphereAuth] init', { base, provider, autoLogin });
    void run(base, provider, autoLogin);
  },
  reset: () => {
    clearUser();
    setFlag(STORAGE_TRIED_KEY, false);
    setFlag(STORAGE_PENDING_KEY, false);
  },
};

if (typeof window !== 'undefined') {
  (window as unknown as { AgentSphereAuth?: AgentSphereAuthApi }).AgentSphereAuth = api;
}

export default api;

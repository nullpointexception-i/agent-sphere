/**
 * 轻量认证入口（与 chat 包分离的独立 IIFE，输出 agent-sphere-auth.js）。
 *
 * 供宿主页面（如 Bole）在**不加载 16MB chat 包**的情况下完成 AS 的 OIDC 静默 SSO：
 * 认证逻辑归属 AS（RP）自身，宿主只加载本脚本并调用 init；浏览器与 AS 的交互
 * 是 RP→IdP 的常规 OIDC 方向，Bole 仅作为 IdP（重定向）与宿主。
 *
 * 流程完成后把 token 写入 sessionStorage['agent-sphere-widget:agent-user']
 * （与 widget auth.ts 同 shape），chrome-extension 读取同一 key 即可建立 task 连接。
 *
 * 静默 SSO 采用**隐藏同源 iframe** 完成 OIDC 一跳（非顶层整页跳转）：
 * IdP 授权页 → AS callback → 302 回落到 redirectUri（同源）并携带 ?otc=；
 * 父窗口轮询 iframe 的 location 提取 otc 后完成兑换，顶层 URL（含 ?open= 等）
 * 全程保持不变，避免新开的标签页被整页刷新“关掉”。
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
const STORAGE_PENDING_KEY = 'agent-sphere:as:auto-sso-pending';
/** 静默 iframe 守卫：iframe 内（同源 SPA）运行的 auth-main 据此跳过自身的 OIDC 处理，防止 OTC 被二次消费。 */
const STORAGE_IFRAME_GUARD_KEY = 'agent-sphere:as:sso-iframe-guard';
const AUTH_RESULT_EVENT = 'agent-sphere:auth-result';
const IFRAME_POLL_INTERVAL_MS = 200;
const IFRAME_POLL_TIMEOUT_MS = 15000;

/** 每次 init/reset 都使旧的异步 SSO 流程失效，避免旧用户结果覆盖新用户。 */
let authGeneration = 0;
let activeProbeController: AbortController | null = null;

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

function isTopWindow(): boolean {
  try {
    return window.self === window.top;
  } catch {
    return false;
  }
}

/** 当前窗口是否为本次静默 iframe 探测产生的同源子窗口。 */
function isSilentProbeFrame(): boolean {
  if (isTopWindow()) {
    return false;
  }
  try {
    return sessionStorage.getItem(STORAGE_IFRAME_GUARD_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * 在隐藏同源 iframe 内执行静默 OIDC 探测，返回落回的 ?otc= 或 ?error=。
 * 整个过程中顶层窗口 URL 保持不变。父窗口仅在 iframe 回落同源后读取其 location。
 */
function silentProbeInFrame(
  base: string,
  provider: string,
  redirectUri: string,
  signal?: AbortSignal,
): Promise<{ otc?: string; error?: string }> {
  return new Promise((resolve) => {
    let settled = false;
    let iframe: HTMLIFrameElement | undefined;
    let interval: number | undefined;
    let removeAbortListener = () => {};

    const cleanup = () => {
      if (iframe) {
        try {
          iframe.remove();
        } catch {
          // ignore
        }
        iframe = undefined;
      }
      try {
        sessionStorage.removeItem(STORAGE_IFRAME_GUARD_KEY);
      } catch {
        // ignore
      }
      removeAbortListener();
    };

    const finish = (result: { otc?: string; error?: string }) => {
      if (settled) {
        return;
      }
      settled = true;
      if (interval) {
        clearInterval(interval);
      }
      cleanup();
      resolve(result);
    };

    if (signal) {
      const onAbort = () => finish({ error: 'cancelled' });
      signal.addEventListener('abort', onAbort, { once: true });
      removeAbortListener = () => signal.removeEventListener('abort', onAbort);
      if (signal.aborted) {
        finish({ error: 'cancelled' });
        return;
      }
    }

    ssoAuthorize(base, provider, redirectUri, 'none')
      .then(({ authorizeUrl }) => {
        if (signal?.aborted || settled) {
          return;
        }
        // 子窗口（回落命中同源 SPA）内的 auth-main 据守卫跳过自身处理
        try {
          sessionStorage.setItem(STORAGE_IFRAME_GUARD_KEY, '1');
        } catch {
          // ignore
        }
        iframe = document.createElement('iframe');
        iframe.setAttribute('aria-hidden', 'true');
        iframe.setAttribute('tabindex', '-1');
        iframe.style.cssText = 'position:absolute;width:1px;height:1px;left:-9999px;top:0;border:0;opacity:0;pointer-events:none;';
        iframe.src = authorizeUrl;
        document.body.appendChild(iframe);
      })
      .catch((err) => {
        console.warn('[AgentSphereAuth] sso authorize failed', err);
        finish({ error: err?.message ?? 'authorize_failed' });
      });

    const startedAt = Date.now();
    interval = window.setInterval(() => {
      if (!iframe) {
        return;
      }
      // 跨源导航期间访问 location 会抛错，回落同源后才可读取
      let href = '';
      try {
        href = iframe.contentWindow?.location?.href ?? '';
      } catch {
        return;
      }
      const params = new URLSearchParams(new URL(href, window.location.origin).search);
      const otc = params.get(SSO_QUERY_PARAM_OTC);
      const errorParam = params.get(SSO_QUERY_PARAM_ERROR);
      if (otc) {
        finish({ otc });
        return;
      }
      if (errorParam) {
        finish({ error: errorParam });
        return;
      }
      if (Date.now() - startedAt > IFRAME_POLL_TIMEOUT_MS) {
        console.warn('[AgentSphereAuth] silent probe timed out');
        finish({ error: 'timeout' });
      }
    }, IFRAME_POLL_INTERVAL_MS);
  });
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

/** 兑换 otc 并落盘 token（复用顶层与 iframe 两种入口）。 */
async function exchangeAndPersist(
  base: string,
  otc: string,
  pending: boolean,
  generation: number,
): Promise<void> {
  try {
    const user = await ssoExchange(base, otc);
    if (generation !== authGeneration) {
      return;
    }
    if (user?.token) {
      setUser(user);
      const merged = await syncSsoIdentity(base, user);
      if (generation !== authGeneration) {
        return;
      }
      setUser(merged);
    }
    setFlag(STORAGE_TRIED_KEY, false);
    console.log('[AgentSphereAuth] exchange ok, token stored', { pending });
    dispatchResult({ via: 'exchange', pending });
  } catch (err) {
    if (generation !== authGeneration) {
      return;
    }
    console.warn('[AgentSphereAuth] exchange failed', err);
    dispatchResult({ via: 'error', pending });
  }
}

async function run(base: string, provider: string, autoLogin: boolean, generation: number): Promise<void> {
  if (generation !== authGeneration) {
    return;
  }
  const otc = readParam(SSO_QUERY_PARAM_OTC);
  const errorParam = readParam(SSO_QUERY_PARAM_ERROR);
  const redirectUri = window.location.origin + window.location.pathname;

  // 静默 iframe 内（同源 SPA 回落）运行的 auth-main：守卫存在时跳过 OIDC 处理，
  // 防止 OTC 被父窗口以外的逻辑二次消费或再次发起探测。
  if (isSilentProbeFrame()) {
    stripParams([SSO_QUERY_PARAM_OTC, SSO_QUERY_PARAM_ERROR]);
    return;
  }

  if (otc) {
    const pending = readFlag(STORAGE_PENDING_KEY);
    setFlag(STORAGE_PENDING_KEY, false);
    await exchangeAndPersist(base, otc, pending, generation);
    stripParams([SSO_QUERY_PARAM_OTC]);
    return;
  }

  if (errorParam) {
    if (generation !== authGeneration) {
      return;
    }
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
  const pending = true;

  // 静默探测改为隐藏同源 iframe，避免整页跳转破坏当前标签页（含 ?open= 等参数）
  const controller = new AbortController();
  activeProbeController = controller;
  const result = await silentProbeInFrame(base, provider, redirectUri, controller.signal);
  if (activeProbeController === controller) {
    activeProbeController = null;
  }
  if (generation !== authGeneration) {
    return;
  }
  if (result.otc) {
    await exchangeAndPersist(base, result.otc, pending, generation);
    return;
  }
  setFlag(STORAGE_PENDING_KEY, false);
  console.warn('[AgentSphereAuth] silent probe returned error:', result.error);
  dispatchResult({ via: 'error', pending });
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
    const generation = ++authGeneration;
    activeProbeController?.abort();
    activeProbeController = null;
    console.log('[AgentSphereAuth] init', { base, provider, autoLogin });
    void run(base, provider, autoLogin, generation);
  },
  reset: () => {
    authGeneration += 1;
    activeProbeController?.abort();
    activeProbeController = null;
    clearUser();
    setFlag(STORAGE_TRIED_KEY, false);
    setFlag(STORAGE_PENDING_KEY, false);
  },
};

if (typeof window !== 'undefined') {
  (window as unknown as { AgentSphereAuth?: AgentSphereAuthApi }).AgentSphereAuth = api;
}

export default api;

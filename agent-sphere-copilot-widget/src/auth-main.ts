/**
 * Lightweight auth entry point (standalone IIFE separate from the chat bundle, emits agent-sphere-auth.js).
 *
 * Lets a host page (such as Bole) complete AS OIDC silent SSO **without loading the 16MB chat bundle**:
 * the auth logic belongs to AS (the RP) itself; the host only loads this script and calls init. Browser-to-AS
 * interaction follows the normal RP->IdP OIDC direction, with Bole acting only as IdP (redirect) and host.
 *
 * After the flow completes the token is written to sessionStorage['agent-sphere-widget:agent-user']
 * (same shape as widget auth.ts), so chrome-extension can read the same key and establish the task connection.
 *
 * Silent SSO uses a **hidden same-origin iframe** for the single OIDC hop (not a top-level full-page redirect):
 * IdP authorize page -> AS callback -> 302 back to redirectUri (same origin) carrying ?otc=;
 * the parent window polls the iframe location, extracts otc and completes the exchange. The top-level URL (including ?open= etc.)
 * stays unchanged throughout, so a freshly opened tab is not "closed" by a full-page reload.
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
const STORAGE_LAST_ATTEMPT_KEY = 'agent-sphere:as:auto-login-last-at';
const STORAGE_FAILURES_KEY = 'agent-sphere:as:auto-login-failures';
/** Silent iframe guard: auth-main running inside the iframe (same-origin SPA) uses it to skip its own OIDC handling, preventing the OTC from being consumed twice. */
const STORAGE_IFRAME_GUARD_KEY = 'agent-sphere:as:sso-iframe-guard';
const AUTH_RESULT_EVENT = 'agent-sphere:auth-result';
const IFRAME_POLL_INTERVAL_MS = 200;
const IFRAME_POLL_TIMEOUT_MS = 15000;
/**
 * Automatic login backoff: when SSO is misconfigured, 401 -> silent SSO -> 401 forms a high-frequency loop.
 * Do not retry within 60 seconds; after 2 consecutive failures stop trying automatically and let the host show the login entry.
 */
const AUTO_LOGIN_MIN_INTERVAL_MS = 60_000;
const AUTO_LOGIN_MAX_FAILURES = 2;

/** Every init/reset invalidates the previous async SSO flow so an old user's result cannot overwrite a new user's. */
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

function readNumber(key: string): number {
  try {
    return Number(sessionStorage.getItem(key) ?? '0') || 0;
  } catch {
    return 0;
  }
}

function setNumber(key: string, value: number): void {
  try {
    sessionStorage.setItem(key, String(value));
  } catch {
    // storage unavailable
  }
}

function clearNumber(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    // storage unavailable
  }
}

/** Whether the current window is the same-origin child window created by this silent iframe probe. */
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
 * Run the silent OIDC probe inside a hidden same-origin iframe and return the resulting ?otc= or ?error=.
 * The top-level window URL stays unchanged the whole time. The parent only reads the iframe location after it falls back same-origin.
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
        // auth-main inside the child window (same-origin SPA fallback) skips its own handling thanks to the guard
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
      // Accessing location during a cross-origin navigation throws; it can only be read after falling back same-origin
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
    // Not SSO or the API failed: keep the existing user and fall back to the identity display
  }
  return user;
}

/** Exchange the otc and persist the token (shared by the top-level and iframe entry points). */
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
      clearNumber(STORAGE_FAILURES_KEY);
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
    if (pending) {
      setNumber(STORAGE_FAILURES_KEY, readNumber(STORAGE_FAILURES_KEY) + 1);
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

  // auth-main running inside the silent iframe (same-origin SPA fallback): when the guard is present, skip OIDC handling
  // so the OTC is not consumed twice by anything other than the parent window or the probe re-triggered.
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

  // Clear the local token outright to support user switching: every init silently logs in the current Bole user again,
  // so a stale token cannot linger, block the flow, or leak across users
  clearUser();
  setFlag(STORAGE_TRIED_KEY, false);

  if (!autoLogin) {
    console.log('[AgentSphereAuth] autoLogin disabled, skip');
    dispatchResult({ via: 'skip' });
    return;
  }

  // Backoff: avoid the 401 -> auto login -> 401 high-frequency loop when SSO is broken
  if (readNumber(STORAGE_FAILURES_KEY) >= AUTO_LOGIN_MAX_FAILURES) {
    console.warn('[AgentSphereAuth] auto login disabled after repeated failures');
    dispatchResult({ via: 'skip' });
    return;
  }
  const lastAttemptAt = readNumber(STORAGE_LAST_ATTEMPT_KEY);
  if (lastAttemptAt > 0 && Date.now() - lastAttemptAt < AUTO_LOGIN_MIN_INTERVAL_MS) {
    console.warn('[AgentSphereAuth] auto login throttled');
    dispatchResult({ via: 'skip' });
    return;
  }
  setNumber(STORAGE_LAST_ATTEMPT_KEY, Date.now());

  setFlag(STORAGE_TRIED_KEY, true);
  setFlag(STORAGE_PENDING_KEY, true);
  const pending = true;

  // Silent probing now uses a hidden same-origin iframe so a full-page redirect cannot disrupt the current tab (including ?open= and friends)
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
  setNumber(STORAGE_FAILURES_KEY, readNumber(STORAGE_FAILURES_KEY) + 1);
  dispatchResult({ via: 'error', pending });
}

export interface AgentSphereAuthApi {
  init: (config?: WidgetConfig) => void;
  /** Clear the session and retry markers; the host calls this after switching users, then calls init again to trigger silent SSO for the new user. */
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
    clearNumber(STORAGE_LAST_ATTEMPT_KEY);
    clearNumber(STORAGE_FAILURES_KEY);
  },
};

if (typeof window !== 'undefined') {
  (window as unknown as { AgentSphereAuth?: AgentSphereAuthApi }).AgentSphereAuth = api;
}

export default api;

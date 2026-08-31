import { useCallback, useEffect, useState } from 'react';
import { createApi, ApiError, type ApiClient } from './api';
import { clearAllSessionData, getUser, setUser } from './auth';
import {
  SSO_QUERY_PARAM_ERROR,
  SSO_QUERY_PARAM_OTC,
  type WidgetConfig,
} from './config';
import type { UserVO } from './types';
import { CopilotView } from './components/CopilotView';
import { LoginView } from './components/LoginView';

type Phase = 'booting' | 'authed' | 'login';

interface RootProps {
  config: WidgetConfig;
}

const AUTO_LOGIN_TRIED_KEY = 'agent-sphere-widget:auto-login-tried';
const LOGOUT_EVENT = 'agent-sphere:logout';

/**
 * 校验缓存用户是否仍是当前宿主用户（identityKey 为 Bole 等宿主的 userId）。
 * 缓存用户通过 /sso/me 得到 ssoSubject（= 宿主 userId 字符串）；不匹配即残留旧会话。
 */
function identityMatches(config: WidgetConfig, u: UserVO | null): boolean {
  const expected = config.identityKey;
  if (expected == null || expected === '') {
    return true;
  }
  if (!u) {
    return false;
  }
  if (u.ssoSubject) {
    return u.ssoSubject === String(expected);
  }
  // 尚未解析出身份信息，无法判定 —— 不误伤
  return true;
}

function readQueryParam(name: string): string | null {
  const params = new URLSearchParams(window.location.search);
  return params.get(name);
}

function stripQueryParams(names: string[]): void {
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

/** 与主站同步：登录后/每次启动拉取 /sso/me，把 providerCode@subject 并入本地用户。 */
async function syncSsoIdentity(api: ApiClient, u: UserVO): Promise<UserVO> {
  try {
    const identity = await api.ssoMe();
    if (identity && identity.providerCode && identity.subject) {
      const merged = {
        ...u,
        ssoProviderCode: identity.providerCode,
        ssoSubject: identity.subject,
      };
      setUser(merged);
      return merged;
    }
  } catch {
    // 非 SSO 或接口异常：忽略，保留原用户
  }
  return u;
}

export function Root({ config }: RootProps) {
  const [phase, setPhase] = useState<Phase>('booting');
  const [user, setUserState] = useState<UserVO | null>(() => getUser());
  const [authError, setAuthError] = useState<string | null>(null);

  const exchange = useCallback(async () => {
    const otc = readQueryParam(SSO_QUERY_PARAM_OTC);
    const errorParam = readQueryParam(SSO_QUERY_PARAM_ERROR);
    if (otc) {
      try {
        const api = createApi(config);
        const exchanged = await api.ssoExchange(otc);
        setUser(exchanged);
        setUserState(exchanged);
        const merged = await syncSsoIdentity(api, exchanged);
        setUserState(merged);
        setPhase('authed');
      } catch (err) {
        setAuthError((err as ApiError).message);
        setPhase('login');
      } finally {
        stripQueryParams([SSO_QUERY_PARAM_OTC]);
      }
      return 'handled';
    }
    if (errorParam) {
      stripQueryParams([SSO_QUERY_PARAM_ERROR]);
      setAuthError(errorParam);
      setPhase('login');
      return 'handled';
    }
    return 'skipped';
  }, [config]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const result = await exchange();
      if (cancelled) {
        return;
      }
      if (result === 'handled') {
        return;
      }
      let stored = getUser();
      if (stored && !identityMatches(config, stored)) {
        // 缓存用户与宿主当前用户不一致（切换后残留旧会话）：丢弃缓存，避免旧 token 继续可用
        clearAllSessionData();
        stored = null;
      }
      if (stored) {
        const api = createApi(config);
        const merged = await syncSsoIdentity(api, stored);
        if (!cancelled) {
          setUserState(merged);
          setPhase('authed');
        }
        return;
      }
      const autoLogin = config.autoLogin !== false;
      const tried = sessionStorage.getItem(AUTO_LOGIN_TRIED_KEY);
      if (autoLogin && !tried) {
        try {
          sessionStorage.setItem(AUTO_LOGIN_TRIED_KEY, '1');
          const redirectUri = window.location.origin + window.location.pathname;
          const api = createApi(config);
          const authorizeUrl = await api.ssoAuthorize(
            config.provider ?? 'business',
            redirectUri,
            'none',
          );
          if (!cancelled) {
            window.location.assign(authorizeUrl);
          }
        } catch (err) {
          if (!cancelled) {
            setAuthError((err as ApiError).message);
            setPhase('login');
          }
        }
        return;
      }
      setPhase('login');
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const handleExternalLogout = () => {
      clearAllSessionData();
      setUserState(null);
      setAuthError(null);
      setPhase('login');
    };
    window.addEventListener(LOGOUT_EVENT, handleExternalLogout);
    return () => window.removeEventListener(LOGOUT_EVENT, handleExternalLogout);
  }, []);

  const handleLogin = useCallback(
    async (provider: string) => {
      try {
        const redirectUri = window.location.origin + window.location.pathname;
        const authorizeUrl = await createApi(config).ssoAuthorize(provider, redirectUri);
        window.location.assign(authorizeUrl);
      } catch (err) {
        setAuthError((err as ApiError).message);
      }
    },
    [config],
  );

  if (phase === 'booting') {
    return <div className="aw-booting">加载中…</div>;
  }

  if (phase === 'login' || !user) {
    return (
      <LoginView
        config={config}
        error={authError}
        onLogin={(provider) => void handleLogin(provider)}
      />
    );
  }

  return <CopilotView config={config} user={user} />;
}
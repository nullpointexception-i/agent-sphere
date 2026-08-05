import { useCallback, useEffect, useState } from 'react';
import { createApi, ApiError } from './api';
import { clearUser, getUser, setUser } from './auth';
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

const AUTO_LOGIN_TRIED_KEY = 'agent-sphere-widget:auto-login-tried';
const LOGOUT_EVENT = 'agent-sphere:logout';

export function Root({ config }: RootProps) {
  const [phase, setPhase] = useState<Phase>('booting');
  const [user, setUserState] = useState<UserVO | null>(() => getUser());
  const [authError, setAuthError] = useState<string | null>(null);

  const exchange = useCallback(async () => {
    const otc = readQueryParam(SSO_QUERY_PARAM_OTC);
    const errorParam = readQueryParam(SSO_QUERY_PARAM_ERROR);
    if (otc) {
      try {
        const exchanged = await createApi(config).ssoExchange(otc);
        setUser(exchanged);
        setUserState(exchanged);
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
      const stored = getUser();
      if (stored) {
        setUserState(stored);
        setPhase('authed');
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
      clearUser();
      sessionStorage.removeItem(AUTO_LOGIN_TRIED_KEY);
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

  const handleLogout = useCallback(() => {
    clearUser();
    setUserState(null);
    setPhase('login');
  }, []);

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

  return <CopilotView config={config} user={user} onLogout={handleLogout} />;
}
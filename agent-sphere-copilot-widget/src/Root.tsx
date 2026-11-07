import { useCallback, useEffect, useState } from 'react';
import { createApi, ApiError, type ApiClient } from './api';
import { clearWidgetUserSession, getUser, setUser } from './auth';
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
 * Check whether the cached user is still the current host user (identityKey is the host's username/display subject).
 * AgentSphere /sso/me currently returns displaySubject as ssoSubject; a mismatch means a stale session is left over.
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
  // Identity not resolved yet, so it cannot be decided - do not act on it
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

/** Stay in sync with the main site: after login / on every start, fetch /sso/me and merge providerCode@subject into the local user. */
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
    // Not SSO or the API failed: ignore and keep the existing user
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
        // The cached user differs from the host's current user (stale session after a switch): drop the cache so the old token cannot stay usable
        clearWidgetUserSession();
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
      clearWidgetUserSession();
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
    return <div className="aw-booting">Loading…</div>;
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

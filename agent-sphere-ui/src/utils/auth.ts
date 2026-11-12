import { getApiOrigin } from './runtimeEnv';

const LEGACY_STORAGE_KEY = 'agent-user';

/**
 * One login per server environment: the desktop app can switch between online and
 * local backends, and each keeps its own session instead of overwriting the other.
 * The web build has no api origin, so it keeps the legacy single key.
 */
function storageKey(): string {
  const origin = getApiOrigin();
  return origin ? `${LEGACY_STORAGE_KEY}:${origin}` : LEGACY_STORAGE_KEY;
}

export interface UserVO {
  id: number;
  username: string;
  displayName: string;
  englishName: string;
  email: string;
  avatar: string;
  token: string;
  status: string;
  demo?: boolean;
  roles?: string[];
  permissions?: string[];
  currentTenantId?: number;
  currentTenantName?: string;
  ssoProviderCode?: string;
  ssoSubject?: string;
}

export function getStoredUser(): UserVO | null {
  try {
    const key = storageKey();
    let stored = localStorage.getItem(key);
    if (!stored && key !== LEGACY_STORAGE_KEY) {
      // First run after the upgrade: adopt the old single-server session for the
      // environment that was active when the app was updated.
      stored = localStorage.getItem(LEGACY_STORAGE_KEY);
      if (stored) {
        localStorage.setItem(key, stored);
        localStorage.removeItem(LEGACY_STORAGE_KEY);
      }
    }
    if (stored) return JSON.parse(stored) as UserVO;
  } catch {}
  return null;
}

export function getToken(): string | null {
  const user = getStoredUser();
  return user?.token ?? null;
}

export function setStoredUser(user: UserVO): void {
  localStorage.setItem(storageKey(), JSON.stringify(user));
}

export function clearStoredUser(): void {
  localStorage.removeItem(storageKey());
}

export function toProCurrentUser(user: UserVO): API.CurrentUser {
  return {
    name: user.displayName || user.username,
    englishName: user.englishName,
    avatar: user.avatar,
    userid: String(user.id),
    email: user.email,
    access: user.username === 'admin' ? 'admin' : 'user',
    demo: user.demo === true,
    ssoProviderCode: user.ssoProviderCode,
    ssoSubject: user.ssoSubject,
  };
}

/** Top-right display name: SSO users show provider@subject (e.g. bole@elvin), otherwise fall back to the English name / full name. */
export function ssoDisplayName(
  user:
    | {
        ssoProviderCode?: string;
        ssoSubject?: string;
        englishName?: string;
        name?: string;
      }
    | undefined,
): string {
  if (user?.ssoProviderCode && user?.ssoSubject) {
    return `${user.ssoProviderCode}@${user.ssoSubject}`;
  }
  return user?.englishName || user?.name || 'User';
}

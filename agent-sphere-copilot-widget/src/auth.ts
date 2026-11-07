import type { UserVO } from './types';

const STORAGE_KEY = 'agent-sphere-widget:agent-user';

/** sessionStorage keys the widget bundle may safely clear. The OIDC iframe keys are managed by auth-main itself. */
export const WIDGET_SESSION_KEYS = {
  user: 'agent-sphere-widget:agent-user',
  autoLoginTried: 'agent-sphere-widget:auto-login-tried',
  activeSession: 'agent-sphere-widget:active-session',
} as const;

let cached: UserVO | null | undefined;

function readRaw(storage: Storage | undefined, key: string): string | null {
  try {
    return storage ? storage.getItem(key) : null;
  } catch {
    return null;
  }
}

function writeRaw(storage: Storage | undefined, key: string, value: string): void {
  try {
    storage?.setItem(key, value);
  } catch {
    // storage unavailable / quota - the in-memory cache is still usable
  }
}

function removeRaw(storage: Storage | undefined, key: string): void {
  try {
    storage?.removeItem(key);
  } catch {
    // ignore
  }
}

/**
 * Read the user token.
 *
 * <p>**localStorage is the primary store**: every tab of the same browser shares one token, so there is a single login state.
 * Older versions wrote to sessionStorage (isolated per tab, which caused "a second tab makes you log in again");
 * when an old value is found it is migrated to localStorage and the old copy is removed.
 */
function readStored(): string | null {
  if (typeof window === 'undefined') return null;
  const shared = readRaw(window.localStorage, STORAGE_KEY);
  if (shared) return shared;
  const legacy = readRaw(window.sessionStorage, STORAGE_KEY);
  if (legacy) {
    writeRaw(window.localStorage, STORAGE_KEY, legacy);
    removeRaw(window.sessionStorage, STORAGE_KEY);
    return legacy;
  }
  return null;
}

export function getUser(): UserVO | null {
  if (cached !== undefined) {
    return cached;
  }
  const raw = readStored();
  try {
    cached = raw ? (JSON.parse(raw) as UserVO) : null;
  } catch {
    cached = null;
  }
  return cached;
}

export function getToken(): string | null {
  return getUser()?.token ?? null;
}

export function setUser(user: UserVO): void {
  cached = user;
  writeRaw(typeof window === 'undefined' ? undefined : window.localStorage,
    STORAGE_KEY, JSON.stringify(user));
  // Migration window: make sure the old copy cannot shadow the new value
  removeRaw(typeof window === 'undefined' ? undefined : window.sessionStorage, STORAGE_KEY);
}

export function clearUser(): void {
  cached = null;
  removeRaw(typeof window === 'undefined' ? undefined : window.localStorage, STORAGE_KEY);
  removeRaw(typeof window === 'undefined' ? undefined : window.sessionStorage, STORAGE_KEY);
}

/** Clear only the widget user cache and the current session; do not disturb an auth attempt in progress. */
export function clearWidgetUserSession(): void {
  clearUser();
  removeRaw(typeof window === 'undefined' ? undefined : window.sessionStorage,
    WIDGET_SESSION_KEYS.activeSession);
}

/** Clear the widget session and let the host start one more authentication attempt. */
export function clearWidgetSession(): void {
  clearWidgetUserSession();
  removeRaw(typeof window === 'undefined' ? undefined : window.sessionStorage,
    WIDGET_SESSION_KEYS.autoLoginTried);
}

/**
 * Wipe widget-side session traces: in-memory cache + the widget's own storage keys.
 *
 * Intended for hosts (such as Bole) to call on user switch/logout. Do not clear the OIDC iframe guard
 * or pending key here, otherwise a running hidden iframe would be mistaken for a normal top-level window and start SSO again.
 */
export function clearAllSessionData(): void {
  clearWidgetSession();
}

// The token is now shared across tabs: when another tab logs out, this page must drop its in-memory cache and close the session,
// otherwise it keeps sending requests with an expired token (manifesting as 401 plus a repeated login prompt).
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event: StorageEvent) => {
    if (event.key !== STORAGE_KEY) {
      return;
    }
    cached = undefined;
    if (event.newValue == null) {
      window.dispatchEvent(new CustomEvent('agent-sphere:logout'));
    }
  });
}

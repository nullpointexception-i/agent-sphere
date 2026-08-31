import type { UserVO } from './types';

const STORAGE_KEY = 'agent-sphere-widget:agent-user';

/** widget 主包可以安全清理的 sessionStorage key。OIDC iframe key 由 auth-main 自己管理。 */
export const WIDGET_SESSION_KEYS = {
  user: 'agent-sphere-widget:agent-user',
  autoLoginTried: 'agent-sphere-widget:auto-login-tried',
  activeSession: 'agent-sphere-widget:active-session',
} as const;

let cached: UserVO | null | undefined;

export function getUser(): UserVO | null {
  if (cached !== undefined) {
    return cached;
  }
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
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
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(user));
  } catch {
    // storage unavailable (e.g. blocked) — keep the in-memory copy
  }
}

export function clearUser(): void {
  cached = null;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

/** 只清理 widget 用户缓存及当前会话，不影响正在运行的认证尝试。 */
export function clearWidgetUserSession(): void {
  cached = null;
  try {
    sessionStorage.removeItem(WIDGET_SESSION_KEYS.user);
    sessionStorage.removeItem(WIDGET_SESSION_KEYS.activeSession);
  } catch {
    // ignore storage unavailable
  }
}

/** 清理 widget 会话并允许宿主重新发起一次认证。 */
export function clearWidgetSession(): void {
  clearWidgetUserSession();
  try {
    sessionStorage.removeItem(WIDGET_SESSION_KEYS.autoLoginTried);
  } catch {
    // ignore storage unavailable
  }
}

/**
 * 清空 widget 侧会话痕迹：内存缓存 + widget 自己的 sessionStorage key。
 *
 * 专供宿主（如 Bole）在用户切换/登出时调用。不要在这里清理 OIDC iframe 的 guard
 * 和 pending key，否则正在运行的隐藏 iframe 会被误判为普通顶层窗口并再次发起 SSO。
 */
export function clearAllSessionData(): void {
  clearWidgetSession();
}

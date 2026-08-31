import type { UserVO } from './types';

const STORAGE_KEY = 'agent-sphere-widget:agent-user';

/**
 * widget 侧所有的 sessionStorage key。auth-main 轻量脚本与 chat 主包各自内联一份，
 * 但 key 必须保持一致，宿主切换用户时才能整体清理掉。
 */
export const WIDGET_SESSION_KEYS = {
  user: 'agent-sphere-widget:agent-user',
  autoLoginTried: 'agent-sphere-widget:auto-login-tried',
  pending: 'bole:as:auto-sso-pending',
  iframeGuard: 'bole:as:sso-iframe-guard',
  activeSession: 'agent-sphere-widget:active-session',
  // 兼容早期版本使用的 pending key
  pendingLegacy: 'agent-sphere:as:auto-sso-pending',
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

/**
 * 清空 widget 侧全部会话痕迹：内存缓存 + 相关 sessionStorage key。
 *
 * 专供宿主（如 Bole）在用户切换/登出时调用。widget 脚本常驻内存、模块级 `cached`
 * 不会因会话清理而重置，仅靠 storage 清理无法让后续聊天气泡读到新用户。
 */
export function clearAllSessionData(): void {
  cached = null;
  try {
    for (const key of Object.values(WIDGET_SESSION_KEYS)) {
      sessionStorage.removeItem(key);
    }
  } catch {
    // ignore storage unavailable
  }
}


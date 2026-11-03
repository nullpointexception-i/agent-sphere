import type { UserVO } from './types';

const STORAGE_KEY = 'agent-sphere-widget:agent-user';

/** widget 主包可以安全清理的 sessionStorage key。OIDC iframe key 由 auth-main 自己管理。 */
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
    // storage unavailable / quota — 内存缓存仍然可用
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
 * 读取用户 token。
 *
 * <p>**主存储是 localStorage**：同一浏览器的所有标签页共用一份 token，登录态只有一个。
 * 旧版本写在 sessionStorage（按标签页隔离，会出现「开第二个标签页又登一次」），
 * 读到旧值时顺手迁移到 localStorage 并清掉旧副本。
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
  // 迁移期：确保旧副本不会盖住新值
  removeRaw(typeof window === 'undefined' ? undefined : window.sessionStorage, STORAGE_KEY);
}

export function clearUser(): void {
  cached = null;
  removeRaw(typeof window === 'undefined' ? undefined : window.localStorage, STORAGE_KEY);
  removeRaw(typeof window === 'undefined' ? undefined : window.sessionStorage, STORAGE_KEY);
}

/** 只清理 widget 用户缓存及当前会话，不影响正在运行的认证尝试。 */
export function clearWidgetUserSession(): void {
  clearUser();
  removeRaw(typeof window === 'undefined' ? undefined : window.sessionStorage,
    WIDGET_SESSION_KEYS.activeSession);
}

/** 清理 widget 会话并允许宿主重新发起一次认证。 */
export function clearWidgetSession(): void {
  clearWidgetUserSession();
  removeRaw(typeof window === 'undefined' ? undefined : window.sessionStorage,
    WIDGET_SESSION_KEYS.autoLoginTried);
}

/**
 * 清空 widget 侧会话痕迹：内存缓存 + widget 自己的存储 key。
 *
 * 专供宿主（如 Bole）在用户切换/登出时调用。不要在这里清理 OIDC iframe 的 guard
 * 和 pending key，否则正在运行的隐藏 iframe 会被误判为普通顶层窗口并再次发起 SSO。
 */
export function clearAllSessionData(): void {
  clearWidgetSession();
}

// token 现在跨标签页共享：别的标签页登出时，本页必须同步丢掉内存缓存并收起会话，
// 否则会继续拿着已失效的 token 发请求（表现为 401 + 反复弹登录）。
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

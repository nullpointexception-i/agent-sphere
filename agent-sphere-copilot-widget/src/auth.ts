import type { UserVO } from './types';

const STORAGE_KEY = 'agent-sphere-widget:agent-user';

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

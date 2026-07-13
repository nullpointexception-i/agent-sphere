export interface UserVO {
  id: number;
  username: string;
  displayName: string;
  englishName: string;
  email: string;
  avatar: string;
  token: string;
  status: string;
  roles?: string[];
  permissions?: string[];
}

export function getStoredUser(): UserVO | null {
  try {
    const stored = localStorage.getItem('agent-user');
    if (stored) return JSON.parse(stored) as UserVO;
  } catch {}
  return null;
}

export function getToken(): string | null {
  const user = getStoredUser();
  return user?.token ?? null;
}

export function setStoredUser(user: UserVO): void {
  localStorage.setItem('agent-user', JSON.stringify(user));
}

export function clearStoredUser(): void {
  localStorage.removeItem('agent-user');
}

export function toProCurrentUser(user: UserVO): API.CurrentUser {
  return {
    name: user.displayName || user.username,
    englishName: user.englishName,
    avatar: user.avatar,
    userid: String(user.id),
    email: user.email,
    access: user.username === 'admin' ? 'admin' : 'user',
  };
}

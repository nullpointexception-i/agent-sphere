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
  ssoProviderCode?: string;
  ssoSubject?: string;
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
    demo: user.demo === true,
    ssoProviderCode: user.ssoProviderCode,
    ssoSubject: user.ssoSubject,
  };
}

/** 右上角展示名：SSO 用户显示 provider@subject（如 bole@elvin），否则回退英文名/姓名。 */
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

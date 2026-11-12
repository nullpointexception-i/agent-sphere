import {
  clearStoredUser,
  getStoredUser,
  setStoredUser,
  type UserVO,
} from '../auth';

function useOrigin(origin: string): void {
  window.__AS_RUNTIME__ = {
    desktop: true,
    apiOrigin: origin,
    webOrigin: origin,
  };
}

function user(token: string, username: string): UserVO {
  return {
    id: 1,
    username,
    displayName: username,
    englishName: username,
    email: '',
    avatar: '',
    token,
    status: 'ACTIVE',
  };
}

describe('per-environment login storage', () => {
  beforeEach(() => {
    localStorage.clear();
    window.__AS_RUNTIME__ = undefined;
  });

  it('keeps one login per server origin', () => {
    useOrigin('https://as.buukle.top');
    setStoredUser(user('online-token', 'alice'));

    useOrigin('http://localhost:8080');
    expect(getStoredUser()).toBeNull();
    setStoredUser(user('local-token', 'bob'));

    useOrigin('https://as.buukle.top');
    expect(getStoredUser()?.token).toBe('online-token');

    useOrigin('http://localhost:8080');
    expect(getStoredUser()?.token).toBe('local-token');
  });

  it('clears only the current environment', () => {
    useOrigin('https://as.buukle.top');
    setStoredUser(user('online-token', 'alice'));
    useOrigin('http://localhost:8080');
    setStoredUser(user('local-token', 'bob'));

    clearStoredUser();

    expect(getStoredUser()).toBeNull();
    useOrigin('https://as.buukle.top');
    expect(getStoredUser()?.token).toBe('online-token');
  });

  it('migrates the legacy single-server session into the current origin', () => {
    localStorage.setItem('agent-user', JSON.stringify(user('legacy', 'carol')));
    useOrigin('https://as.buukle.top');

    expect(getStoredUser()?.token).toBe('legacy');
    expect(localStorage.getItem('agent-user')).toBeNull();
    expect(localStorage.getItem('agent-user:https://as.buukle.top')).toContain(
      'legacy',
    );
  });

  it('keeps the legacy key in the web build without an api origin', () => {
    useOrigin('');
    setStoredUser(user('web-token', 'dave'));
    expect(localStorage.getItem('agent-user')).toContain('web-token');
    expect(getStoredUser()?.token).toBe('web-token');
  });
});

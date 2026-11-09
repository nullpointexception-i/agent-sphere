import { userDisplayLabel } from '../userLabel';

describe('userDisplayLabel', () => {
  it('prefers the external identity label', () => {
    expect(
      userDisplayLabel({
        ssoProviderCode: 'bole',
        ssoSubject: 'elvin',
        displayName: 'Bob',
        username: 'bole_2',
      }),
    ).toBe('bole@elvin');
  });

  it('falls back to display name, then English name, then username', () => {
    expect(userDisplayLabel({ displayName: 'Bob', username: 'bob' })).toBe(
      'Bob',
    );
    expect(userDisplayLabel({ englishName: 'Bob Lee', username: 'bob' })).toBe(
      'Bob Lee',
    );
    expect(userDisplayLabel({ username: 'bob' })).toBe('bob');
  });

  it('uses the provided fallback', () => {
    expect(userDisplayLabel(null, 'Unknown')).toBe('Unknown');
  });
});

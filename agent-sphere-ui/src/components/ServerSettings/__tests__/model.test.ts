import {
  MAX_RECENT_SERVERS,
  mergeRecentServers,
  normalizeServerUrl,
  presetIdOf,
  safeOrigin,
} from '../model';

describe('server settings model', () => {
  it('normalizes trailing slashes and paths to an origin', () => {
    expect(normalizeServerUrl(' https://as.buukle.top/ ')).toBe(
      'https://as.buukle.top',
    );
    expect(normalizeServerUrl('http://localhost:8080/workspace')).toBe(
      'http://localhost:8080',
    );
  });

  it('rejects unsupported protocols', () => {
    expect(() => normalizeServerUrl('ftp://example.com')).toThrow();
    expect(() => normalizeServerUrl('not-a-url')).toThrow();
  });

  it('keeps recent servers newest-first, unique and bounded', () => {
    expect(
      mergeRecentServers('http://localhost:8080', [
        'https://as.buukle.top',
        'http://localhost:8080',
      ]),
    ).toEqual(['http://localhost:8080', 'https://as.buukle.top']);

    const many = Array.from(
      { length: 8 },
      (_, index) => `http://host-${index}`,
    );
    expect(mergeRecentServers('http://new-host', many)).toHaveLength(
      MAX_RECENT_SERVERS,
    );
  });

  it('identifies presets and custom addresses', () => {
    expect(presetIdOf('https://as.buukle.top')).toBe('online');
    expect(presetIdOf('http://localhost:8080')).toBe('local');
    expect(presetIdOf('https://staging.example.com')).toBe('custom');
  });

  it('never throws when reading a malformed saved address', () => {
    expect(safeOrigin(undefined)).toBe('');
    expect(safeOrigin('broken')).toBe('broken');
  });
});

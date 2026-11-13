import { parseWorkspaceQuery, toWorkspaceSearch } from '../workspaceQuery';

describe('workspaceQuery', () => {
  it('defaults to home when the query is empty or unknown', () => {
    expect(parseWorkspaceQuery('')).toEqual({ tab: 'home' });
    expect(parseWorkspaceQuery('?tab=unknown')).toEqual({ tab: 'home' });
  });

  it('parses app, scene, project and requirement deep links', () => {
    expect(
      parseWorkspaceQuery(
        '?tab=apps&appKey=system-projects&appId=12&sceneKey=project-list&projectId=34&requirementId=56',
      ),
    ).toEqual({
      tab: 'apps',
      appKey: 'system-projects',
      appId: 12,
      sceneKey: 'project-list',
      projectId: 34,
      requirementId: 56,
    });
  });

  it('drops empty and invalid values', () => {
    expect(
      parseWorkspaceQuery(
        '?tab=apps&appKey=%20&appId=abc&sceneKey=&projectId=-1',
      ),
    ).toEqual({ tab: 'apps' });
  });

  it('serializes a stable search string and omits home', () => {
    expect(toWorkspaceSearch({ tab: 'home' })).toBe('');
    expect(
      toWorkspaceSearch({
        tab: 'apps',
        appKey: 'system-projects',
        sceneKey: 'project-qa',
      }),
    ).toBe('?tab=apps&appKey=system-projects&sceneKey=project-qa');
  });

  it('round-trips a deep link', () => {
    const query = {
      tab: 'apps' as const,
      appKey: 'system-projects',
      appId: 12,
      sceneKey: 'requirement-board',
      projectId: 34,
      requirementId: 56,
    };
    expect(parseWorkspaceQuery(toWorkspaceSearch(query))).toEqual(query);
  });
});

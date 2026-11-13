export const WORKSPACE_TABS = [
  'home',
  'avatars',
  'chat',
  'projects',
  'apps',
  'documents',
  'contacts',
  'me',
] as const;

export type Tab = (typeof WORKSPACE_TABS)[number];

export interface WorkspaceQuery {
  tab: Tab;
  appKey?: string;
  appId?: number;
  sceneKey?: string;
  sceneId?: number;
  projectId?: number;
  requirementId?: number;
}

const TAB_SET = new Set<string>(WORKSPACE_TABS);

function textParam(params: URLSearchParams, key: string) {
  const value = params.get(key)?.trim();
  return value ? value : undefined;
}

function numberParam(params: URLSearchParams, key: string) {
  const value = params.get(key);
  if (!value) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

export function parseWorkspaceQuery(search: string): WorkspaceQuery {
  const params = new URLSearchParams(
    search.startsWith('?') ? search.slice(1) : search,
  );
  const tabParam = params.get('tab');
  const tab =
    tabParam && TAB_SET.has(tabParam) ? (tabParam as Tab) : ('home' as Tab);

  return {
    tab,
    appKey: textParam(params, 'appKey'),
    appId: numberParam(params, 'appId'),
    sceneKey: textParam(params, 'sceneKey'),
    sceneId: numberParam(params, 'sceneId'),
    projectId: numberParam(params, 'projectId'),
    requirementId: numberParam(params, 'requirementId'),
  };
}

export function toWorkspaceSearch(query: Partial<WorkspaceQuery>): string {
  const params = new URLSearchParams();
  if (query.tab && query.tab !== 'home') params.set('tab', query.tab);
  if (query.appKey) params.set('appKey', query.appKey);
  if (query.appId) params.set('appId', String(query.appId));
  if (query.sceneKey) params.set('sceneKey', query.sceneKey);
  if (query.sceneId) params.set('sceneId', String(query.sceneId));
  if (query.projectId) params.set('projectId', String(query.projectId));
  if (query.requirementId) {
    params.set('requirementId', String(query.requirementId));
  }
  const value = params.toString();
  return value ? `?${value}` : '';
}

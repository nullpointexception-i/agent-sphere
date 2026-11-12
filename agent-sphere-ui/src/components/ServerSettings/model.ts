/** Server-address helpers shared by the connection settings UI and its tests. */

export interface ServerPreset {
  id: 'online' | 'local';
  url: string;
}

export const SERVER_PRESETS: ServerPreset[] = [
  { id: 'online', url: 'https://as.buukle.top' },
  { id: 'local', url: 'http://localhost:8080' },
];

export const MAX_RECENT_SERVERS = 5;

/** Normalize a user-entered server address to its origin. Throws on invalid input. */
export function normalizeServerUrl(value: string): string {
  const url = new URL(value.trim());
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('protocol');
  }
  return url.origin;
}

/** Newest-first recent list with de-duplication and a bounded size. */
export function mergeRecentServers(
  current: string | undefined,
  recent: string[] | undefined,
): string[] {
  const merged = current ? [current, ...(recent ?? [])] : [...(recent ?? [])];
  const seen = new Set<string>();
  const result: string[] = [];
  for (const item of merged) {
    if (!item || seen.has(item)) continue;
    seen.add(item);
    result.push(item);
    if (result.length >= MAX_RECENT_SERVERS) break;
  }
  return result;
}

/** Which preset (if any) matches the given address. */
export function presetIdOf(origin: string): 'online' | 'local' | 'custom' {
  const preset = SERVER_PRESETS.find((item) => item.url === origin);
  return preset ? preset.id : 'custom';
}

/** Safe display helper that never throws on malformed settings. */
export function safeOrigin(value: string | undefined): string {
  if (!value) return '';
  try {
    return normalizeServerUrl(value);
  } catch {
    return value;
  }
}

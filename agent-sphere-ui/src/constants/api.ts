import { getApiOrigin } from '@/utils/runtimeEnv';

/**
 * Shared API base for every hand-written service client.
 *
 * Web builds use a relative `/api/v1`; the desktop build injects an absolute
 * server origin through `window.__AS_RUNTIME__`.
 */
const ORIGIN = typeof window === 'undefined' ? '' : getApiOrigin();

export const API_BASE = `${ORIGIN}/api/v1`;

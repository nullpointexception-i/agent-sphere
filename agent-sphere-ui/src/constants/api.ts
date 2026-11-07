/**
 * Shared API base for every hand-written service client.
 *
 * <p>Keep this value in sync with the backend {@code ApiPrefix.V1} constant. The front-end
 * cannot import Java constants, so this is the single client-side source of truth.
 */
export const API_BASE = '/api/v1';

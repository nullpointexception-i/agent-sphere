/**
 * Module-level cache of attachment-bytes objectURLs:
 * - Concurrent requests for the same fileKey are deduplicated (the in-flight promise is reused), avoiding duplicate fetch / createObjectURL calls;
 * - Cache hits return immediately, so render churn no longer triggers repeated downloads (fixes ERR_INSUFFICIENT_RESOURCES);
 * - AbortController support: clearFileCache aborts in-flight requests and releases every objectURL.
 *
 * Lifetime: the cache belongs to the module; components only read and never revoke. Call clearFileCache to reclaim everything on session switch/unmount.
 */

interface CacheEntry {
  url?: string;
  promise?: Promise<string>;
  controller?: AbortController;
}

const cache = new Map<string, CacheEntry>();

/** Get (or load) an objectURL; concurrent calls for the same key are deduplicated; the result is cached until clearFileCache. */
export function loadCachedObjectUrl(
  cacheKey: string,
  loader: (fileKey: string, signal: AbortSignal) => Promise<Blob>,
  fileKey: string,
): Promise<string> {
  const existing = cache.get(cacheKey);
  if (existing?.url) return Promise.resolve(existing.url);
  if (existing?.promise) return existing.promise;

  const entry: CacheEntry = existing ?? {};
  const controller = new AbortController();
  entry.controller = controller;
  const promise = loader(fileKey, controller.signal)
    .then((blob) => {
      entry.url = URL.createObjectURL(blob);
      entry.promise = undefined;
      entry.controller = undefined;
      return entry.url;
    })
    .catch((err) => {
      entry.promise = undefined;
      entry.controller = undefined;
      if (!entry.url) cache.delete(cacheKey);
      throw err;
    });
  entry.promise = promise;
  cache.set(cacheKey, entry);
  return promise;
}

/** Clear the cache: abort in-flight requests and release all objectURLs (called on session switch / component unmount). */
export function clearFileCache(): void {
  for (const entry of cache.values()) {
    entry.controller?.abort();
    if (entry.url) {
      try {
        URL.revokeObjectURL(entry.url);
      } catch {
        /* ignore */
      }
    }
  }
  cache.clear();
}

/** Current number of cache entries (for debugging). */
export function cachedObjectUrlCount(): number {
  return cache.size;
}

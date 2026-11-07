export interface SseCallbacks {
  onMessage?: (payload: string) => void;
  onOpen?: () => void;
  onError?: (error: Error) => void;
}

/**
 * Open an SSE connection and consume data: JSON lines (same as the main site's utils/sse.ts):
 * - The connect-timeout guard covers only the initial fetch phase; it is cleared as soon as headers arrive, so a live stream is not killed by mistake.
 * - watchdog: 60s without any data is treated as a dead link and triggers onError to reconnect.
 * - Abort from an external signal returns silently.
 */
export async function connectSse(
  url: string,
  token: string,
  callbacks: SseCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const CONNECT_TIMEOUT_MS = 20000;
  const controller = new AbortController();
  const abortFromExternal = () => controller.abort();
  if (signal) {
    if (signal.aborted) return;
    signal.addEventListener('abort', abortFromExternal, { once: true });
  }
  let timedOut = false;
  const connectTimer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, CONNECT_TIMEOUT_MS);

  try {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        'Accept-Encoding': 'identity',
        'Cache-Control': 'no-cache',
      },
      signal: controller.signal,
    });

    if (!response.ok) {
      callbacks.onError?.(new Error(`SSE connection failed: ${response.status}`));
      return;
    }

    // Connection established (headers received): clear the initial fetch guard.
    clearTimeout(connectTimer);

    const reader = response.body?.getReader();
    if (!reader) throw new Error('ReadableStream not supported');

    callbacks.onOpen?.();

    const decoder = new TextDecoder();
    let buffer = '';
    let lastPing = Date.now();

    const watchdog = setInterval(() => {
      if (Date.now() - lastPing > 60000) {
        callbacks.onError?.(new Error('SSE timeout'));
        clearInterval(watchdog);
        void reader.cancel();
      }
    }, 10000);

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          callbacks.onError?.(new Error('SSE stream ended'));
          return;
        }

        buffer += decoder.decode(value, { stream: true });
        lastPing = Date.now();

        const parts = buffer.split('\n\n');
        buffer = parts.pop() || '';

        for (const part of parts) {
          const trimmed = part.trim();
          if (!trimmed || trimmed.startsWith(':')) continue;

          const dataLines: string[] = [];
          for (const line of trimmed.split('\n')) {
            if (line.startsWith('data:')) {
              dataLines.push(line.slice(5).trim());
            }
          }
          if (dataLines.length > 0) {
            callbacks.onMessage?.(dataLines.join('\n'));
          }
        }
      }
    } finally {
      clearInterval(watchdog);
    }
  } catch (error: unknown) {
    if (timedOut) {
      callbacks.onError?.(new Error('SSE connect timeout'));
      return;
    }
    if (error instanceof DOMException && error.name === 'AbortError') return;
    callbacks.onError?.(error as Error);
  } finally {
    clearTimeout(connectTimer);
    if (signal) signal.removeEventListener('abort', abortFromExternal);
  }
}
import { TIMING } from '@/constants/timing';

export type SseCallbacks = {
  onMessage?: (payload: string) => void;
  onEvent?: (eventType: string, payload: string) => void;
  onOpen?: () => void;
  onError?: (error: Error) => void;
};

export async function connectSse(
  url: string,
  token: string,
  callbacks: SseCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  // Connect timeout: when the initial fetch hangs (half-open connection / proxy buffering, etc.)
  // and neither resolves nor rejects,
  // onError must be triggered so the caller can go through reconnect backoff; otherwise SSE never
  // connects (recoverable only by a manual refresh).
  const CONNECT_TIMEOUT_MS = TIMING.SSE_CONNECT_TIMEOUT_MS;
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
      cache: 'no-store',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      signal: controller.signal,
    });

    if (!response.ok) {
      callbacks.onError?.(
        new Error(`SSE connection failed: ${response.status}`),
      );
      return;
    }

    // Connection established (headers received): clear the initial fetch guard. If this timer were
    // only cleared in finally,
    // a normal stream alive >20s would be wrongly aborted, killing a healthy SSE connection and
    // reconnecting repeatedly.
    clearTimeout(connectTimer);

    const reader = response.body?.getReader();
    if (!reader) {
      callbacks.onError?.(new Error('ReadableStream not supported'));
      return;
    }

    callbacks.onOpen?.();

    const decoder = new TextDecoder();
    let buffer = '';
    let lastPing = Date.now();

    const watchdog = setInterval(() => {
      if (Date.now() - lastPing > 60000) {
        callbacks.onError?.(new Error('SSE timeout'));
        clearInterval(watchdog);
        reader.cancel();
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
          if (!trimmed) continue;
          if (trimmed.startsWith(':')) continue;

          const lines = trimmed.split('\n');
          let eventType = 'message';
          const dataLines: string[] = [];
          for (const line of lines) {
            if (line.startsWith('event:')) {
              eventType = line.slice(6).trim() || 'message';
            } else if (line.startsWith('data:')) {
              dataLines.push(line.slice(5).trim());
            }
          }

          if (dataLines.length > 0) {
            const data = dataLines.join('\n');
            callbacks.onEvent?.(eventType, data);
            callbacks.onMessage?.(data);
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

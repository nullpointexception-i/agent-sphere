export type SseCallbacks = {
  onMessage?: (payload: string) => void;
  onOpen?: () => void;
  onError?: (error: Error) => void;
};

export async function connectSse(
  url: string,
  token: string,
  callbacks: SseCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  try {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      signal,
    });

    if (!response.ok) {
      callbacks.onError?.(
        new Error(`SSE connection failed: ${response.status}`),
      );
      return;
    }

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
          const dataLines: string[] = [];
          for (const line of lines) {
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
    if (error instanceof DOMException && error.name === 'AbortError') return;
    callbacks.onError?.(error as Error);
  }
}

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
  // 连接超时：初始 fetch 挂起（半开/代理缓冲等）既不 resolve 也不 reject 时，
  // 必须触发 onError 让上层走重连退避，否则 SSE 永远连不上（只能靠手动刷新恢复）。
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

    // 连接已建立（headers 到手）：解除初始 fetch 守卫。此定时器若拖到 finally 才清，
    // 会对存活 >20s 的正常流误触发 abort，把好端端的 SSE 连接杀掉并反复重连。
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

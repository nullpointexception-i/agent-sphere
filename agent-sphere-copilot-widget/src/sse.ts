export interface SseCallbacks {
  onMessage?: (payload: string) => void;
  onOpen?: () => void;
  onError?: (error: Error) => void;
}

/**
 * 建立 SSE 连接并消费 data: JSON 行（与主站 utils/sse.ts 同款）：
 * - 连接超时守护只覆盖初始 fetch 阶段；headers 到手即解除，避免误杀存活中的流。
 * - watchdog：60s 无任何数据则视为死链，触发 onError 走重连。
 * - 外部 signal abort 时静默返回。
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

    // 连接已建立（headers 到手）：解除初始 fetch 守卫。
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
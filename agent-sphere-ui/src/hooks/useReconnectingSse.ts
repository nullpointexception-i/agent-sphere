import { useEffect, useRef } from 'react';
import { getToken } from '@/utils/auth';
import { connectSse } from '@/utils/sse';

const MAX_RETRY_DELAY_MS = 30000;

export interface ImStreamPayload {
  type?: string;
  conversationId?: number;
  messageId?: number;
  seq?: number;
  unread?: number;
  timestamp?: number;
  previewText?: string;
  createdAt?: string;
  title?: string;
  pinned?: boolean;
  muted?: boolean;
  remark?: string;
  // agent_stream（Router 实时流）字段
  threadId?: number;
  runId?: number;
  agentId?: number;
  /** thinking / reply / status */
  phase?: string;
  delta?: string;
  /** started / completed / failed / cancelled / tool_pending / tool_succeeded / tool_failed */
  streamStatus?: string;
  toolName?: string;
  displayName?: string;
  errorMessage?: string;
  streamSeq?: number;
  // message_created 附带的发送方信息
  senderType?: string;
  senderId?: number;
  clientMsgId?: string;
}

/**
 * Keeps an authenticated SSE stream alive with exponential backoff and
 * dispatches the SSE event name + parsed payload.
 */
export function useReconnectingSse(
  url: string,
  onEvent: (type: string, event: ImStreamPayload) => void,
) {
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    const token = getToken();
    if (!token) return;
    let stopped = false;
    let retry = 0;
    let timer: number | undefined;
    let controller = new AbortController();

    const run = async () => {
      controller = new AbortController();
      await connectSse(
        url,
        token,
        {
          onOpen: () => {
            retry = 0;
          },
          onEvent: (eventType, payload) => {
            let parsed: ImStreamPayload = {};
            try {
              parsed = JSON.parse(payload) as ImStreamPayload;
            } catch {
              parsed = {};
            }
            handlerRef.current(parsed.type || eventType, parsed);
          },
          onError: () => {
            if (stopped) return;
            const delay = Math.min(MAX_RETRY_DELAY_MS, 1000 * 2 ** retry);
            retry += 1;
            timer = window.setTimeout(run, delay);
          },
        },
        controller.signal,
      );
    };

    const reconnect = () => {
      if (stopped) return;
      if (timer) window.clearTimeout(timer);
      controller.abort();
      retry = 0;
      void run();
    };

    const onVisible = () => {
      if (document.visibilityState === 'visible') reconnect();
    };
    const onOnline = () => reconnect();

    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', onOnline);
    window.addEventListener('focus', onVisible);

    run();
    return () => {
      stopped = true;
      controller.abort();
      if (timer) window.clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', onOnline);
      window.removeEventListener('focus', onVisible);
    };
  }, [url]);
}

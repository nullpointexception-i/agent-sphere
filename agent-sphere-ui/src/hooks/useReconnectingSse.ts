import { useEffect, useRef } from 'react';
import { TIMING } from '@/constants/timing';
import { getToken } from '@/utils/auth';
import { connectSse } from '@/utils/sse';

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
  // agent_stream (Router live stream) fields
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
  // Sender info carried by message_created
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
  onOpen?: () => void,
) {
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;
  const openRef = useRef(onOpen);
  openRef.current = onOpen;

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
            openRef.current?.();
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
            const delay = Math.min(
              TIMING.SSE_RECONNECT_MAX_MS,
              TIMING.SSE_RETRY_BASE_MS * 2 ** retry,
            );
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

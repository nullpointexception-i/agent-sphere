import {
  createContext,
  type PropsWithChildren,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
} from 'react';
import { API_PATHS } from '@/constants/endpoints';
import {
  type ImStreamPayload,
  useReconnectingSse,
} from '@/hooks/useReconnectingSse';

export interface ImStreamEvent {
  type: string;
  data: ImStreamPayload;
  nonce: number;
}

interface ImStreamValue {
  /** Last stream event. Consumers dedupe by `nonce`. */
  event: ImStreamEvent | null;
}

const ImStreamContext = createContext<ImStreamValue | null>(null);

/**
 * Single IM SSE connection for the whole workspace. Chat lists and conversation
 * panes subscribe to the same event instead of opening their own streams.
 */
export function ImStreamProvider({ children }: PropsWithChildren) {
  const [event, setEvent] = useState<ImStreamEvent | null>(null);
  const nonceRef = useRef(0);

  const publish = useCallback((type: string, data: ImStreamPayload) => {
    if (type === 'heartbeat') return;
    nonceRef.current += 1;
    setEvent({ type, data, nonce: nonceRef.current });
  }, []);

  useReconnectingSse(
    API_PATHS.IM_STREAM,
    publish,
    // Server does not replay missed events; consumers refetch on reconnect.
    () => publish('sse_reconnected', {}),
  );

  const value = useMemo(() => ({ event }), [event]);

  return (
    <ImStreamContext.Provider value={value}>
      {children}
    </ImStreamContext.Provider>
  );
}

export function useImStream() {
  const value = useContext(ImStreamContext);
  if (!value) {
    throw new Error('useImStream must be used within ImStreamProvider');
  }
  return value;
}

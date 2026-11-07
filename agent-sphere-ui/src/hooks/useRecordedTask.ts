import { useCallback, useEffect, useRef, useState } from 'react';
import { RECORDED_TASK_TERMINAL_STATUSES, STATUS } from '@/constants/status';
import { TIMING } from '@/constants/timing';

/**
 * Polls the execution record of a "long-running background task" until it reaches a
 * terminal state.
 *
 * <p>Session cleanup (session-cleanup) and Skill auto-sync (skill sync-now) share the same
 * shape: the submit endpoint immediately returns a record with `status=RUNNING`, the real
 * work happens in the background, and the frontend can only poll for progress.
 * This hook extracts that logic for both call sites.
 *
 * <p>A record may stay in RUNNING forever (if the process crashed); the backend flags it
 * with `stale`, which is used here to stop polling — otherwise it would be a timer that
 * never stops.
 *
 * @param runId Record id; null means do not poll (panel closed)
 * @param fetchOne Request that fetches a single record
 * @param onFinish Called when a terminal state is reached (including stale), used to refresh the list
 */
export function useRecordedTask<T extends { status: string; stale?: boolean }>(
  runId: number | null,
  fetchOne: (id: number) => Promise<T>,
  onFinish?: (record: T) => void,
) {
  const [record, setRecord] = useState<T | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stoppedRef = useRef(false);
  // Keep the callback in a ref: callers pass a new arrow function every render, so putting
  // it in deps would re-run the effect on every frame
  const onFinishRef = useRef(onFinish);
  onFinishRef.current = onFinish;

  const stop = useCallback(() => {
    stoppedRef.current = true;
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  useEffect(() => {
    stoppedRef.current = false;
    if (runId == null) {
      setRecord(null);
      return;
    }

    let cancelled = false;
    const tick = async () => {
      try {
        const next = await fetchOne(runId);
        if (cancelled || stoppedRef.current) return;
        setRecord(next);
        if (RECORDED_TASK_TERMINAL_STATUSES.has(next.status) || next.stale) {
          stoppedRef.current = true;
          onFinishRef.current?.(next);
          return;
        }
      } catch {
        // A single failed poll does not terminate: retry on the next tick so network
        // jitter does not make an in-flight task look finished
        if (cancelled || stoppedRef.current) return;
      }
      if (!cancelled && !stoppedRef.current) {
        timerRef.current = setTimeout(tick, TIMING.POLL_INTERVAL_MS);
      }
    };

    tick();
    return () => {
      cancelled = true;
      stop();
    };
    // fetchOne is inlined by the caller (e.g. (id) => agentApi.skill.syncRun(id)) and is a
    // new reference on every render, so it is intentionally left out of the dep array:
    // only runId really changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, stop]);

  return {
    record,
    stop,
    running:
      !!record && record.status === STATUS.CLEANUP.RUNNING && !record.stale,
  };
}

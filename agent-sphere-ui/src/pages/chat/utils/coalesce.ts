/**
 * Trailing-edge coalescing + single flight: consecutive triggers run only once after waitMs;
 * triggers arriving during a run are merged into "run again after completion", so the backend
 * is never flooded with concurrency.
 *
 * Used for high-frequency requests such as timeline tail refresh and runtime state refresh.
 */
export function createCoalescedTask(
  task: (sessionId: number) => Promise<void>,
  waitMs: number,
): (sessionId: number) => void {
  let timer: number | null = null;
  let running = false;
  let pending: number | null = null;

  const run = async () => {
    if (running) return;
    running = true;
    try {
      while (pending != null) {
        const sessionId = pending;
        pending = null;
        try {
          await task(sessionId);
        } catch {
          // Request errors are left to the caller / global prompt; this only keeps the
          // coalescing queue from stalling.
        }
      }
    } finally {
      running = false;
    }
  };

  return (sessionId: number) => {
    pending = sessionId;
    if (timer != null || running) return;
    timer = window.setTimeout(() => {
      timer = null;
      void run();
    }, waitMs);
  };
}

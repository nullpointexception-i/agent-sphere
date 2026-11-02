/**
 * 尾沿合并 + 单飞：连续触发只在 waitMs 后执行一次；执行期间再来的触发
 * 合并成「完成后再跑一次」，不会并发打满后端。
 *
 * 用于 timeline 尾页刷新、runtime state 刷新等高频道请求。
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
          // 请求错误交给调用方/全局提示；这里只保证合并队列不中断。
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

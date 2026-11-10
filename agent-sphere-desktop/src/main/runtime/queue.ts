import type { LocalTaskPayload } from '../api/contracts';

export interface QueuedTask {
  task: LocalTaskPayload;
  leaseToken: string;
}

function keyOf(task: LocalTaskPayload): string {
  return `${task.agentId}:${task.thread.id}`;
}

/**
 * Global concurrency plus per-(agent, thread) serialization. The server already
 * dispatches one batch per conversation, but retries can overlap.
 */
export class TaskScheduler {
  private readonly pending: QueuedTask[] = [];
  private readonly activeKeys = new Set<string>();
  private readonly activeTaskIds = new Set<number>();
  private active = 0;

  constructor(
    private readonly concurrency: () => number,
    private readonly worker: (item: QueuedTask) => Promise<void>,
  ) {}

  enqueue(item: QueuedTask): void {
    if (this.pending.some((row) => row.task.taskId === item.task.taskId)) return;
    if (this.activeTaskIds.has(item.task.taskId)) return;
    this.pending.push(item);
    this.pump();
  }

  runningIds(): number[] {
    return [
      ...this.activeTaskIds,
      ...this.pending.map((item) => item.task.taskId),
    ];
  }

  clearPending(): void {
    this.pending.length = 0;
  }

  private pump(): void {
    while (this.active < Math.max(1, this.concurrency())) {
      const index = this.pending.findIndex((item) => !this.activeKeys.has(keyOf(item.task)));
      if (index < 0) break;

      const [item] = this.pending.splice(index, 1);
      if (!item) break;

      const key = keyOf(item.task);
      this.active += 1;
      this.activeKeys.add(key);
      this.activeTaskIds.add(item.task.taskId);

      void this.worker(item)
        .catch(() => undefined)
        .finally(() => {
          this.active -= 1;
          this.activeKeys.delete(key);
          this.activeTaskIds.delete(item.task.taskId);
          this.pump();
        });
    }
  }
}

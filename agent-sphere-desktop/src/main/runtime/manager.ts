import { app } from 'electron';
import type {
  DriverStatus,
  RuntimeStatus,
  TaskView,
} from '../../shared/types';
import type { SettingsStore } from '../config/settings';
import type { ProfileStore } from '../config/profiles';
import type { LocalApiClient } from '../api/client';
import type { DriverRegistry } from './drivers/registry';
import type { ApprovalBroker } from './approval';
import { TaskRunner } from './runner';
import { TaskScheduler } from './queue';
import { logger } from '../logging/logger';

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class RuntimeManager {
  private status: RuntimeStatus = {
    state: 'UNCONFIGURED',
    since: Date.now(),
    runningTasks: 0,
    driverStatus: [],
  };
  private readonly tasks = new Map<number, TaskView>();
  private readonly runner: TaskRunner;
  private readonly scheduler: TaskScheduler;
  private heartbeatTimer?: NodeJS.Timeout;
  private streamController?: AbortController;
  private reconnectTimer?: NodeJS.Timeout;
  private started = false;
  private heartbeatFailures = 0;

  constructor(
    private readonly deps: {
      api: LocalApiClient;
      settings: SettingsStore;
      profiles: ProfileStore;
      drivers: DriverRegistry;
      approvals: ApprovalBroker;
    },
    private readonly emitRuntime: (status: RuntimeStatus) => void,
    private readonly emitTask: (task: TaskView) => void,
  ) {
    this.runner = new TaskRunner(deps, (task) => this.upsertTask(task));
    this.scheduler = new TaskScheduler(
      () => this.deps.settings.get().maxConcurrentTasks,
      (item) => this.runner.run(item),
    );
  }

  async start(): Promise<void> {
    if (this.started) return;
    this.started = true;
    if (!this.deps.settings.isConfigured()) {
      this.setStatus('UNCONFIGURED', 'Runtime is not configured');
      return;
    }
    await this.connect();
  }

  async restart(): Promise<void> {
    this.stop();
    this.started = true;
    if (!this.deps.settings.isConfigured()) {
      this.setStatus('UNCONFIGURED', 'Runtime is not configured');
      return;
    }
    await this.connect();
  }

  stop(): void {
    this.started = false;
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.heartbeatTimer = undefined;
    this.reconnectTimer = undefined;
    this.streamController?.abort();
    this.streamController = undefined;
    this.scheduler.clearPending();
  }

  getStatus(): RuntimeStatus {
    return {
      ...this.status,
      runningTasks: this.scheduler.runningIds().length,
    };
  }

  listTasks(): TaskView[] {
    return [...this.tasks.values()].sort(
      (left, right) => (right.startedAt ?? right.taskId) - (left.startedAt ?? left.taskId),
    );
  }

  async cancelTask(taskId: number): Promise<void> {
    await this.runner.cancel(taskId);
  }

  pause(): void {
    this.setStatus('PAUSED', 'Task intake paused');
    this.scheduler.clearPending();
  }

  resume(): void {
    if (!this.deps.settings.isConfigured()) {
      this.setStatus('UNCONFIGURED', 'Runtime is not configured');
      return;
    }
    this.setStatus('ONLINE');
    void this.heartbeat();
    this.startStream();
  }

  async detectDrivers(): Promise<DriverStatus[]> {
    const statuses = await this.deps.drivers.detectAll();
    this.status = { ...this.status, driverStatus: statuses };
    this.emitRuntime(this.getStatus());
    return statuses;
  }

  private async connect(): Promise<void> {
    if (!this.started) return;
    this.setStatus('CONNECTING', 'Connecting to AgentSphere');
    try {
      const info = await this.deps.api.getRuntime();
      logger.info(`local runtime online: ${info.name} (${info.id})`);
      this.heartbeatFailures = 0;
      this.setStatus('ONLINE');
      this.startHeartbeat();
      this.startStream();
      void this.detectDrivers();
    } catch (error) {
      logger.warn(`runtime connect failed: ${String(error)}`);
      this.setStatus('OFFLINE', 'Unable to reach AgentSphere');
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect(): void {
    if (!this.started || this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      void this.connect();
    }, 5_000);
  }

  private startHeartbeat(): void {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    void this.heartbeat();
    this.heartbeatTimer = setInterval(() => {
      void this.heartbeat();
    }, 20_000);
  }

  private async heartbeat(): Promise<void> {
    if (!this.started) return;
    try {
      await this.deps.api.heartbeat({
        version: app.getVersion(),
        os: process.platform,
        arch: process.arch,
        runningTaskIds: this.scheduler.runningIds(),
      });
      this.heartbeatFailures = 0;
      if (this.status.state === 'OFFLINE' && this.status.message !== 'Task intake paused') {
        this.setStatus('ONLINE');
        this.startStream();
      }
    } catch (error) {
      this.heartbeatFailures += 1;
      logger.warn(`heartbeat failed (${this.heartbeatFailures}): ${String(error)}`);
      if (this.heartbeatFailures >= 3) {
        this.setStatus('OFFLINE', 'Connection lost; retrying');
      }
    }
  }

  private startStream(): void {
    if (this.streamController) return;
    const controller = new AbortController();
    this.streamController = controller;
    void this.streamLoop(controller);
  }

  private async streamLoop(controller: AbortController): Promise<void> {
    while (this.started && !controller.signal.aborted) {
      try {
        await this.deps.api.streamTasks((event) => this.onStreamEvent(event), controller.signal);
      } catch (error) {
        if (controller.signal.aborted) return;
        logger.warn(`task stream failed: ${String(error)}`);
        this.setStatus('OFFLINE', 'Connection lost; retrying');
      }
      await delay(5_000);
    }
    if (this.streamController === controller) {
      this.streamController = undefined;
    }
  }

  private onStreamEvent(event: { type?: string; taskId?: number }): void {
    if (event.type !== 'task_ready') return;
    const taskId = Number(event.taskId);
    if (!Number.isFinite(taskId)) return;
    void this.acceptTask(taskId);
  }

  private async acceptTask(taskId: number): Promise<void> {
    if (!this.started || this.status.state === 'PAUSED') return;
    const existing = this.tasks.get(taskId);
    if (existing && !['FAILED', 'COMPLETED', 'CANCELLED'].includes(existing.status)) return;

    this.upsertTask({ taskId, status: 'RECEIVED' });
    try {
      const ack = await this.deps.api.ack(taskId);
      this.upsertTask({
        taskId,
        agentId: ack.task.agentId,
        agentName: ack.task.agentName,
        threadId: ack.task.thread.id,
        threadTitle: ack.task.thread.title,
        status: 'QUEUED',
      });
      this.scheduler.enqueue({ task: ack.task, leaseToken: ack.leaseToken });
    } catch (error) {
      logger.warn(`task ${taskId} ack failed: ${String(error)}`);
      this.upsertTask({
        taskId,
        status: 'FAILED',
        error: String(error),
        finishedAt: Date.now(),
      });
    }
  }

  private upsertTask(view: TaskView): void {
    const existing = this.tasks.get(view.taskId);
    const merged: TaskView = {
      ...existing,
      ...view,
      startedAt: existing?.startedAt ?? view.startedAt ?? (view.status === 'QUEUED' ? Date.now() : undefined),
    };
    this.tasks.set(view.taskId, merged);
    this.emitTask(merged);
    if (this.tasks.size > 200) {
      const completed = [...this.tasks.values()]
        .filter((task) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status))
        .sort((left, right) => left.taskId - right.taskId);
      for (const task of completed.slice(0, completed.length - 100)) {
        this.tasks.delete(task.taskId);
      }
    }
  }

  private setStatus(state: RuntimeStatus['state'], message?: string): void {
    this.status = {
      ...this.status,
      state,
      message,
      since: Date.now(),
      runningTasks: this.scheduler.runningIds().length,
    };
    this.emitRuntime(this.getStatus());
  }

  dispose(): void {
    this.stop();
    this.deps.drivers.dispose();
  }
}

import { existsSync, statSync } from 'node:fs';
import type { AgentProfile, TaskView } from '../../shared/types';
import type { LocalTaskPayload } from '../api/contracts';
import type { LocalApiClient } from '../api/client';
import type { ProfileStore } from '../config/profiles';
import type { SettingsStore } from '../config/settings';
import type { ApprovalBroker } from './approval';
import type { DriverRegistry } from './drivers/registry';
import type { QueuedTask } from './queue';
import { buildPrompt } from './prompt';
import { logger } from '../logging/logger';

function isMention(task: LocalTaskPayload): boolean {
  const reason = (task.trigger?.reason ?? '').toLowerCase();
  return reason.includes('mention') || reason.includes('dm') || reason.includes('direct');
}

class ChunkBuffer {
  private sequence = 0;
  private timer?: NodeJS.Timeout;
  private chain: Promise<void> = Promise.resolve();
  private readonly pending = new Map<'reply' | 'thinking', string>();

  constructor(
    private readonly send: (
      seq: number,
      phase: 'reply' | 'thinking',
      delta: string,
    ) => Promise<void>,
  ) {}

  push(phase: 'reply' | 'thinking', delta: string): void {
    if (!delta) return;
    this.pending.set(phase, (this.pending.get(phase) ?? '') + delta);
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.flush();
    }, 200);
  }

  async flush(): Promise<void> {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    for (const [phase, text] of this.pending) {
      if (!text) continue;
      this.pending.set(phase, '');
      const seq = ++this.sequence;
      this.chain = this.chain
        .then(() => this.send(seq, phase, text))
        .catch((error: unknown) => {
          logger.warn(`chunk upload failed: ${String(error)}`);
        });
    }
    await this.chain;
  }
}

export class TaskRunner {
  private readonly controllers = new Map<number, AbortController>();

  constructor(
    private readonly deps: {
      api: LocalApiClient;
      settings: SettingsStore;
      profiles: ProfileStore;
      drivers: DriverRegistry;
      approvals: ApprovalBroker;
    },
    private readonly onTask: (view: TaskView) => void,
  ) {}

  async run(item: QueuedTask): Promise<void> {
    const { task, leaseToken } = item;
    const profile = this.deps.profiles.find(task.agentId);
    if (!profile) {
      await this.fail(
        task,
        leaseToken,
        'NO_PROFILE',
        `No local settings saved for ${task.agentName}. Open the avatar, choose a workspace and save the local settings.`,
      );
      return;
    }
    if (!profile.enabled) {
      await this.fail(
        task,
        leaseToken,
        'PROFILE_DISABLED',
        `Local settings are disabled for ${task.agentName}. Enable them in the avatar drawer.`,
      );
      return;
    }

    if (!this.workspaceExists(profile)) {
      await this.fail(
        task,
        leaseToken,
        'WORKSPACE_INVALID',
        `Workspace does not exist: ${profile.workspace}`,
      );
      return;
    }

    if (profile.autoRun === 'mention-only' && !isMention(task)) {
      await this.fail(
        task,
        leaseToken,
        'NOT_MENTIONED',
        'Task was not a direct mention and the profile only runs on mentions',
      );
      return;
    }

    const driver = this.deps.drivers.get(profile.driver);
    let driverStatus = this.deps.drivers
      .getStatuses()
      .find((status) => status.kind === profile.driver);
    if (!driverStatus || driverStatus.state !== 'READY') {
      try {
        driverStatus = await driver.detect();
      } catch (error) {
        await this.fail(
          task,
          leaseToken,
          'DRIVER_NOT_READY',
          error instanceof Error ? error.message : String(error),
        );
        return;
      }
    }
    if (driverStatus.state !== 'READY') {
      await this.fail(
        task,
        leaseToken,
        'DRIVER_NOT_READY',
        driverStatus.message ?? `${profile.driver} is not ready`,
      );
      return;
    }

    if (profile.autoRun === 'confirm') {
      const decision = await this.deps.approvals.request({
        taskId: task.taskId,
        kind: 'task.start',
        category: 'input',
        title: 'Run local task?',
        detail: `${task.agentName}\n${task.thread.title ?? `#${task.thread.id}`}\n\n${
          task.messages.at(-1)?.text ?? ''
        }`,
      });
      if (decision === 'decline' || decision === 'cancel') {
        await this.fail(task, leaseToken, 'USER_DECLINED', 'The user declined this task');
        return;
      }
    }

    const controller = new AbortController();
    this.controllers.set(task.taskId, controller);
    const prompt = buildPrompt(task);
    let sessionRef = task.sessionRef;
    const chunks = new ChunkBuffer((seq, phase, delta) =>
      this.deps.api.chunk(task.taskId, leaseToken, seq, phase, delta),
    );

    const deadline = task.deadlineAt
      ? new Date(task.deadlineAt).getTime()
      : Number.POSITIVE_INFINITY;
    const timeoutMs = Math.max(
      1,
      Math.min(this.deps.settings.get().taskTimeoutSec * 1000, deadline - Date.now()),
    );
    const timer = setTimeout(() => {
      void driver.cancel(task.taskId);
      controller.abort();
    }, timeoutMs);

    this.update({
      taskId: task.taskId,
      agentId: task.agentId,
      agentName: task.agentName,
      threadId: task.thread.id,
      threadTitle: task.thread.title,
      status: 'RUNNING',
      startedAt: Date.now(),
    });

    try {
      const result = await driver.run({
        task,
        profile,
        prompt,
        signal: controller.signal,
        approvals: this.deps.approvals,
        onChunk: (phase, delta) => chunks.push(phase, delta),
        onSessionRef: (ref) => {
          sessionRef = ref;
        },
        onStatus: (status) => {
          if (status === 'WAITING_APPROVAL' || status === 'WAITING_INPUT') {
            this.update({
              taskId: task.taskId,
              agentId: task.agentId,
              agentName: task.agentName,
              threadId: task.thread.id,
              threadTitle: task.thread.title,
              status: 'WAITING_APPROVAL',
              startedAt: Date.now(),
            });
          }
        },
      });

      await chunks.flush();
      await this.deps.api.complete(task.taskId, leaseToken, {
        text: result.text,
        sessionRef: result.sessionRef ?? sessionRef,
      });
      this.update({
        taskId: task.taskId,
        agentId: task.agentId,
        agentName: task.agentName,
        threadId: task.thread.id,
        threadTitle: task.thread.title,
        status: 'COMPLETED',
        startedAt: Date.now(),
        finishedAt: Date.now(),
      });
    } catch (error) {
      await chunks.flush().catch(() => undefined);
      const aborted = controller.signal.aborted;
      const message = error instanceof Error ? error.message : String(error);
      await this.fail(
        task,
        leaseToken,
        aborted ? 'CANCELLED' : 'LOCAL_TASK_FAILED',
        message,
      );
      this.update({
        taskId: task.taskId,
        agentId: task.agentId,
        agentName: task.agentName,
        threadId: task.thread.id,
        threadTitle: task.thread.title,
        status: aborted ? 'CANCELLED' : 'FAILED',
        startedAt: Date.now(),
        finishedAt: Date.now(),
        error: message,
      });
    } finally {
      clearTimeout(timer);
      this.controllers.delete(task.taskId);
    }
  }

  async cancel(taskId: number): Promise<void> {
    const controller = this.controllers.get(taskId);
    controller?.abort();
  }

  private workspaceExists(profile: AgentProfile): boolean {
    try {
      return existsSync(profile.workspace) && statSync(profile.workspace).isDirectory();
    } catch {
      return false;
    }
  }

  private async fail(
    task: LocalTaskPayload,
    leaseToken: string,
    code: string,
    message: string,
  ): Promise<void> {
    logger.warn(`task ${task.taskId} failed: ${code} ${message}`);
    try {
      await this.deps.api.fail(task.taskId, leaseToken, code, message);
    } catch (error) {
      logger.warn(`failed to report task failure: ${String(error)}`);
    }
    this.update({
      taskId: task.taskId,
      agentId: task.agentId,
      agentName: task.agentName,
      threadId: task.thread.id,
      threadTitle: task.thread.title,
      status: 'FAILED',
      startedAt: Date.now(),
      finishedAt: Date.now(),
      error: `${code}: ${message}`,
    });
  }

  private update(view: TaskView): void {
    this.onTask(view);
  }
}

/**
 * Shared status metadata for tables and task cards.
 *
 * <p>Keep domain groups separate: a work item, a task and an agent do not share the same
 * lifecycle even when they use the same word (for example ACTIVE).
 */
export interface StatusMeta {
  color: string;
  labelKey: string;
}

export const SUPER_AGENT_STATUS: Record<string, StatusMeta> = {
  ACTIVE: { color: 'green', labelKey: 'pages.superAgent.agentStatus.active' },
  PAUSED: { color: 'orange', labelKey: 'pages.superAgent.agentStatus.paused' },
  ARCHIVED: {
    color: 'default',
    labelKey: 'pages.superAgent.agentStatus.archived',
  },
};

export const WORK_ITEM_STATUS: Record<string, StatusMeta> = {
  QUEUED: { color: 'blue', labelKey: 'pages.superAgent.workStatus.queued' },
  DISPATCHING: {
    color: 'processing',
    labelKey: 'pages.superAgent.workStatus.dispatching',
  },
  RUNNING: {
    color: 'processing',
    labelKey: 'pages.superAgent.workStatus.running',
  },
  WAITING: { color: 'gold', labelKey: 'pages.superAgent.workStatus.waiting' },
  COMPLETED: {
    color: 'green',
    labelKey: 'pages.superAgent.workStatus.completed',
  },
  DONE: { color: 'green', labelKey: 'pages.superAgent.workStatus.completed' },
  FAILED: { color: 'red', labelKey: 'pages.superAgent.workStatus.failed' },
  CANCELLED: {
    color: 'default',
    labelKey: 'pages.superAgent.workStatus.cancelled',
  },
};

export const TASK_STATUS: Record<string, StatusMeta> = {
  QUEUED: { color: 'blue', labelKey: 'pages.admin.tasks.statusQueued' },
  RUNNING: { color: 'processing', labelKey: 'pages.admin.tasks.statusRunning' },
  COMPLETED: { color: 'green', labelKey: 'pages.admin.tasks.statusCompleted' },
  FAILED: { color: 'red', labelKey: 'pages.admin.tasks.statusFailed' },
  CANCELLED: {
    color: 'default',
    labelKey: 'pages.admin.tasks.statusCancelled',
  },
};

export const IM_TASK_STATUS: Record<string, StatusMeta> = {
  QUEUED: { color: 'blue', labelKey: 'pages.im.status.queued' },
  DISPATCHING: { color: 'processing', labelKey: 'pages.im.status.dispatching' },
  RUNNING: { color: 'processing', labelKey: 'pages.im.status.running' },
  WAITING: { color: 'gold', labelKey: 'pages.im.status.waiting' },
  COMPLETED: { color: 'green', labelKey: 'pages.im.status.completed' },
  DONE: { color: 'green', labelKey: 'pages.im.status.completed' },
  FAILED: { color: 'red', labelKey: 'pages.im.status.failed' },
  CANCELLED: { color: 'default', labelKey: 'pages.im.status.cancelled' },
};

export const CLEANUP_RUN_STATUS: Record<string, StatusMeta> = {
  RUNNING: {
    color: 'processing',
    labelKey: 'pages.admin.settings.cleanup.runs.statusRunning',
  },
  SUCCESS: {
    color: 'green',
    labelKey: 'pages.admin.settings.cleanup.runs.statusSuccess',
  },
  FAILED: {
    color: 'red',
    labelKey: 'pages.admin.settings.cleanup.runs.statusFailed',
  },
  SKIPPED: {
    color: 'default',
    labelKey: 'pages.admin.settings.cleanup.runs.statusSkipped',
  },
};

export const SKILL_SYNC_RUN_STATUS: Record<string, StatusMeta> = {
  RUNNING: {
    color: 'processing',
    labelKey: 'pages.admin.settings.skillSyncRuns.running',
  },
  SUCCESS: {
    color: 'green',
    labelKey: 'pages.admin.settings.skillSyncRuns.success',
  },
  FAILED: {
    color: 'red',
    labelKey: 'pages.admin.settings.skillSyncRuns.failed',
  },
  SKIPPED: {
    color: 'default',
    labelKey: 'pages.admin.settings.skillSyncRuns.skipped',
  },
};

export const STATUS = {
  RUN: {
    QUEUED: 'QUEUED',
    PENDING: 'PENDING',
    RUNNING: 'RUNNING',
    COMPLETED: 'COMPLETED',
    FAILED: 'FAILED',
    CANCELLED: 'CANCELLED',
    AWAITING_USER: 'AWAITING_USER',
    WAITING: 'WAITING',
  },
  WORK_ITEM: {
    QUEUED: 'QUEUED',
    DISPATCHING: 'DISPATCHING',
    RUNNING: 'RUNNING',
    WAITING: 'WAITING',
    BLOCKED: 'BLOCKED',
    COMPLETED: 'COMPLETED',
    DONE: 'DONE',
    FAILED: 'FAILED',
    CANCELLED: 'CANCELLED',
  },
  TASK: {
    QUEUED: 'QUEUED',
    RUNNING: 'RUNNING',
    COMPLETED: 'COMPLETED',
    FAILED: 'FAILED',
    CANCELLED: 'CANCELLED',
  },
  CLEANUP: {
    RUNNING: 'RUNNING',
    SUCCESS: 'SUCCESS',
    FAILED: 'FAILED',
    SKIPPED: 'SKIPPED',
  },
  SKILL_SYNC: {
    RUNNING: 'RUNNING',
    SUCCESS: 'SUCCESS',
    FAILED: 'FAILED',
    SKIPPED: 'SKIPPED',
  },
  ACTIVE: 'ACTIVE',
  DISABLED: 'DISABLED',
  ENABLED: 'ENABLED',
} as const;

/** Work-item states that still occupy a Worker slot. */
export const WORK_ITEM_ACTIVE_STATUSES = new Set<string>([
  STATUS.WORK_ITEM.QUEUED,
  STATUS.WORK_ITEM.DISPATCHING,
  STATUS.WORK_ITEM.RUNNING,
  STATUS.WORK_ITEM.WAITING,
]);

/** Run states that are terminal. */
export const RUN_TERMINAL_STATUSES = new Set<string>([
  STATUS.RUN.COMPLETED,
  STATUS.RUN.FAILED,
  STATUS.RUN.CANCELLED,
]);

/** Background task records shared by session cleanup and skill sync. */
export const RECORDED_TASK_TERMINAL_STATUSES = new Set<string>([
  STATUS.CLEANUP.SUCCESS,
  STATUS.CLEANUP.FAILED,
  STATUS.CLEANUP.SKIPPED,
]);

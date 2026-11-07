import {
  RECORDED_TASK_TERMINAL_STATUSES,
  RUN_TERMINAL_STATUSES,
  STATUS,
  WORK_ITEM_ACTIVE_STATUSES,
} from '../status';

describe('status constants', () => {
  it('keeps run status values stable', () => {
    expect(STATUS.RUN.RUNNING).toBe('RUNNING');
    expect(STATUS.RUN.COMPLETED).toBe('COMPLETED');
    expect(STATUS.RUN.FAILED).toBe('FAILED');
  });

  it('marks terminal run states', () => {
    expect(RUN_TERMINAL_STATUSES.has(STATUS.RUN.COMPLETED)).toBe(true);
    expect(RUN_TERMINAL_STATUSES.has(STATUS.RUN.FAILED)).toBe(true);
    expect(RUN_TERMINAL_STATUSES.has(STATUS.RUN.CANCELLED)).toBe(true);
    expect(RUN_TERMINAL_STATUSES.has(STATUS.RUN.RUNNING)).toBe(false);
  });

  it('marks active work-item states', () => {
    expect(WORK_ITEM_ACTIVE_STATUSES.has(STATUS.WORK_ITEM.QUEUED)).toBe(true);
    expect(WORK_ITEM_ACTIVE_STATUSES.has(STATUS.WORK_ITEM.RUNNING)).toBe(true);
    expect(WORK_ITEM_ACTIVE_STATUSES.has(STATUS.WORK_ITEM.COMPLETED)).toBe(
      false,
    );
  });

  it('marks recorded background-task terminal states', () => {
    expect(RECORDED_TASK_TERMINAL_STATUSES.has(STATUS.CLEANUP.SUCCESS)).toBe(
      true,
    );
    expect(RECORDED_TASK_TERMINAL_STATUSES.has(STATUS.CLEANUP.FAILED)).toBe(
      true,
    );
    expect(RECORDED_TASK_TERMINAL_STATUSES.has(STATUS.CLEANUP.SKIPPED)).toBe(
      true,
    );
    expect(RECORDED_TASK_TERMINAL_STATUSES.has(STATUS.CLEANUP.RUNNING)).toBe(
      false,
    );
  });
});

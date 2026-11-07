/**
 * Timeline row states.
 *
 * <p>Run-level states are uppercase (they mirror the backend run status), while tool and
 * clarification states arrive lowercase/mixed from the raw event payload. Keep both spellings
 * here instead of scattering literals through the renderers.
 */
export const TIMELINE_STATE = {
  RUNNING: 'RUNNING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
  PENDING: 'PENDING',
  WAITING: 'WAITING',
  AWAITING_USER: 'AWAITING_USER',
  TIMEOUT: 'TIMEOUT',
  // tool / clarification states
  TOOL_RUNNING: 'running',
  TOOL_PENDING: 'tool_pending',
  TOOL_COMPLETED: 'completed',
  TOOL_FAILED: 'failed',
  TOOL_ERROR: 'error',
  TOOL_CANCELLED: 'cancelled',
  TOOL_AWAITING_USER: 'awaiting_user',
  TOOL_SUCCEEDED: 'succeeded',
  SUCCESS: 'success',
  ANSWERED: 'ANSWERED',
} as const;

/** Tool/clarification states that count as a successful end state. */
export const COMPLETED_TIMELINE_STATES: string[] = [
  TIMELINE_STATE.TOOL_SUCCEEDED,
  TIMELINE_STATE.TOOL_COMPLETED,
  TIMELINE_STATE.SUCCESS,
];

/** Todo-item states returned by the todo tool. */
export const TODO_STATUS = {
  COMPLETED: 'completed',
  CANCELLED: 'cancelled',
} as const;

/** Shared timing and state values for the embeddable widget. */

export const TIMING = {
  SLOW_TOOL_MS: 5000,
  COPY_RESET_MS: 2000,
  CLOCK_TICK_MS: 1000,
  SSE_CONNECT_TIMEOUT_MS: 20000,
  SSE_RECONNECT_BASE_MS: 1000,
  SSE_RECONNECT_MAX_MS: 30000,
} as const;

/** Uppercase run states returned by the backend. */
export const RUN_STATE = {
  RUNNING: 'RUNNING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED',
  WAITING: 'WAITING',
  PENDING: 'PENDING',
  AWAITING_USER: 'AWAITING_USER',
} as const;

/** Lowercase tool/clarification states carried by tool payloads. */
export const TOOL_STATE = {
  RUNNING: 'running',
  COMPLETED: 'completed',
  FAILED: 'failed',
  ERROR: 'error',
  CANCELLED: 'cancelled',
  SUCCEEDED: 'succeeded',
  SUCCESS: 'success',
} as const;

export const TODO_STATUS = {
  COMPLETED: 'completed',
  CANCELLED: 'cancelled',
} as const;

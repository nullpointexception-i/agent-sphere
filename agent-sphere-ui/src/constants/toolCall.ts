export const TODOWRITE_TOOL_NAME = 'builtin_3';
export const DOCWRITE_TOOL_NAME = 'builtin_6';

export const TOOL_CALL_RECORD_STATUS = {
  PENDING: 'PENDING',
  SUCCEEDED: 'SUCCEEDED',
  FAILED: 'FAILED',
} as const;

export type ToolCallRecordStatus = (typeof TOOL_CALL_RECORD_STATUS)[keyof typeof TOOL_CALL_RECORD_STATUS];

export interface ApprovalToolCall {
  callId: string;
  toolName?: string;
  displayName?: string;
  action?: string;
  arguments?: unknown;
  approvalScope?: string;
  pending?: boolean;
}

export type ApprovalOption =
  | 'DENY'
  | 'ALLOW_ONCE'
  | 'ALLOW_RUN'
  | 'ALLOW_SESSION';

export function parseApprovalCalls(json?: string): ApprovalToolCall[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (row) =>
        row && typeof row === 'object' && row.callId && row.pending !== false,
    );
  } catch {
    return [];
  }
}

export function optionsForScope(scope?: string): ApprovalOption[] {
  if (scope === 'SESSION') {
    return ['DENY', 'ALLOW_ONCE', 'ALLOW_RUN', 'ALLOW_SESSION'];
  }
  if (scope === 'RUN') {
    return ['DENY', 'ALLOW_ONCE', 'ALLOW_RUN'];
  }
  return ['DENY', 'ALLOW_ONCE'];
}

export function prettyArguments(raw?: unknown): string {
  if (raw === undefined || raw === null) return '';
  const text = typeof raw === 'string' ? raw : JSON.stringify(raw);
  if (!text) return '';
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

export function approvalSourceLabel(sessionType?: string): string {
  switch (sessionType) {
    case 'WORKER':
      return 'Worker';
    case 'TRAINING':
      return 'Training';
    case 'ROUTER':
      return 'Router';
    case 'GROUP_ROUTER':
      return 'Group';
    default:
      return sessionType || 'Agent';
  }
}

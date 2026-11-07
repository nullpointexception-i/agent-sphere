/**
 * Pure helpers and lookup tables for the chat timeline.
 *
 * <p>Kept free of React so the large `TimelineList` component only owns rendering.
 */
import { TIMELINE_STATE } from '@/constants/timeline';

/** Resolved state of a timeline row, falling back from content to the row snapshot. */
export const displayState = (row: any): string | undefined =>
  row?.kind === 'run_status'
    ? (row?.state ?? row?.content?.state)
    : (row?.content?.state ?? row?.state);

export const KIND_LABEL_KEY: Record<string, string> = {
  user: 'pages.chat.timeline.kind.user',
  assistant: 'pages.chat.timeline.kind.assistant',
  tool: 'pages.chat.timeline.kind.tool',
  clarification: 'pages.chat.timeline.kind.clarification',
  tool_approval: 'pages.chat.timeline.kind.toolApproval',
  subagent: 'pages.chat.timeline.kind.subagent',
  compaction: 'pages.chat.timeline.kind.compaction',
  run_status: 'pages.chat.timeline.kind.runStatus',
  error: 'pages.chat.timeline.kind.error',
};

// DOM cap for long sessions: beyond this only the latest N rows are rendered, with a
// "show earlier" button at the top.
export const DEFAULT_RENDER_LIMIT = 300;
export const RENDER_LIMIT_STEP = 200;

// Internal protocol tools: the finish answer is already shown as assistant body text, and
// ask_clarification is shown by the clarification row.
export const FINISH_TOOL_NAME = 'builtin_12';
export const ASK_CLARIFICATION_TOOL_NAME = 'builtin_7';

export function isInternalToolRow(row: any): boolean {
  if (!row || row.kind !== 'tool') {
    return false;
  }
  const content = row.content;
  return (
    content?.toolName === FINISH_TOOL_NAME ||
    content?.toolName === ASK_CLARIFICATION_TOOL_NAME ||
    content?.displayName === '完成任务' ||
    content?.displayName === 'Finish' ||
    content?.displayName === '请求澄清' ||
    content?.displayName === 'Ask Clarification'
  );
}

export function formatDuration(ms?: number) {
  if (ms == null || Number.isNaN(ms) || ms < 0) return '';
  if (ms < 1000) return '<1s';
  const totalSec = Math.round(ms / 1000);
  if (totalSec < 60) return `${totalSec}s`;
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return s ? `${m}m${s}s` : `${m}m`;
}

export function toFiniteNumber(value: any): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

export function formatWaitDuration(ms?: number | null): string | null {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return null;
  const seconds = Math.max(1, Math.round(ms / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest === 0 ? `${minutes}m` : `${minutes}m${rest}s`;
}

export function formatWaitClock(
  value: string | null,
  epochMs: number | null,
): string | null {
  const date =
    epochMs != null
      ? new Date(epochMs)
      : value
        ? new Date(value.replace(' ', 'T'))
        : null;
  if (!date || Number.isNaN(date.getTime())) return null;
  return date.toLocaleTimeString('zh-CN', { hour12: false });
}

export function parseWaitView(content: any) {
  const meta = content?.meta ?? {};
  let args: any = null;
  let artifact: any = null;
  try {
    args = content?.args ? JSON.parse(content.args) : null;
  } catch {
    // ignore malformed args
  }
  try {
    artifact = content?.artifact ? JSON.parse(content.artifact) : null;
  } catch {
    // ignore malformed artifact
  }
  return {
    waitMs: toFiniteNumber(meta.waitMs) ?? toFiniteNumber(args?.ms),
    elapsedMs:
      toFiniteNumber(meta.elapsedMs) ??
      toFiniteNumber(artifact?.elapsed_ms) ??
      toFiniteNumber(meta.durationMs) ??
      toFiniteNumber(args?.ms),
    startedAtMs: toFiniteNumber(meta.startedAtMs),
    resumedAtMs:
      toFiniteNumber(meta.resumedAtMs) ??
      toFiniteNumber(artifact?.resumed_at_ms),
    resumedAtRaw:
      typeof meta.resumedAt === 'string'
        ? meta.resumedAt
        : typeof artifact?.resumed_at === 'string'
          ? artifact.resumed_at
          : null,
    error: typeof meta.error === 'string' ? meta.error : null,
  };
}

export const TEXT_MATCH_MODE_LABEL: Record<string, string> = {
  EXACT: 'pages.chat.tool.modeExact',
  IGNORE_CASE: 'pages.chat.tool.modeIgnoreCase',
  TRIMMED: 'pages.chat.tool.modeTrimmed',
  IGNORE_CASE_TRIMMED: 'pages.chat.tool.modeIgnoreCaseTrimmed',
};

export function prettyJsonSafe(raw?: string) {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

export function parseJsonArray(raw?: string): any[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function isSucceededState(state: any) {
  return String(state ?? '').toLowerCase() === TIMELINE_STATE.TOOL_SUCCEEDED;
}

export function subAgentMemberKey(row: any): string {
  const id = row?.refSubAgentRunId;
  return String(id ?? row?.seq ?? Math.random());
}

/** Grouping key for parallel sub-agents in the same batch: shared parentToolCallId (derived from the same delegate call). */
export function parallelGroupKey(row: any): string | null {
  if (row?.kind !== 'subagent') return null;
  const pid = row?.content?.parentToolCallId;
  if (pid == null || pid === '') return null;
  return String(pid);
}

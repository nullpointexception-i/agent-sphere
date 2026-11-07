import {
  displayState,
  formatDuration,
  formatWaitClock,
  formatWaitDuration,
  isInternalToolRow,
  isSucceededState,
  parallelGroupKey,
  parseJsonArray,
  parseWaitView,
  prettyJsonSafe,
  subAgentMemberKey,
  toFiniteNumber,
} from '../timelineModel';

describe('timelineModel', () => {
  it('prefers run_status state over content state', () => {
    expect(displayState({ kind: 'run_status', state: 'RUNNING' })).toBe(
      'RUNNING',
    );
    expect(
      displayState({ kind: 'run_status', content: { state: 'COMPLETED' } }),
    ).toBe('COMPLETED');
  });

  it('prefers content state for normal rows', () => {
    expect(
      displayState({
        kind: 'tool',
        state: 'RUNNING',
        content: { state: 'ok' },
      }),
    ).toBe('ok');
  });

  it('detects internal protocol tool rows', () => {
    expect(isInternalToolRow({ kind: 'tool', content: {} })).toBe(false);
    expect(
      isInternalToolRow({ kind: 'tool', content: { toolName: 'builtin_12' } }),
    ).toBe(true);
    expect(
      isInternalToolRow({
        kind: 'tool',
        content: { displayName: 'Ask Clarification' },
      }),
    ).toBe(true);
  });

  it('formats durations', () => {
    expect(formatDuration(undefined)).toBe('');
    expect(formatDuration(500)).toBe('<1s');
    expect(formatDuration(15_000)).toBe('15s');
    expect(formatDuration(75_000)).toBe('1m15s');
    expect(formatDuration(120_000)).toBe('2m');
  });

  it('converts finite numbers only', () => {
    expect(toFiniteNumber(12)).toBe(12);
    expect(toFiniteNumber('12.5')).toBe(12.5);
    expect(toFiniteNumber('x')).toBeNull();
    expect(toFiniteNumber(null)).toBeNull();
  });

  it('formats wait durations and clocks', () => {
    expect(formatWaitDuration(null)).toBeNull();
    expect(formatWaitDuration(30_000)).toBe('30s');
    expect(formatWaitDuration(90_000)).toBe('1m30s');
    expect(formatWaitDuration(120_000)).toBe('2m');
    expect(formatWaitClock(null, 0)).not.toBeNull();
    expect(formatWaitClock('not-a-date', null)).toBeNull();
  });

  it('parses wait view meta with args fallback', () => {
    const view = parseWaitView({
      meta: { startedAtMs: 1000, error: 'boom' },
      args: JSON.stringify({ ms: 5000 }),
    });
    expect(view.waitMs).toBe(5000);
    expect(view.startedAtMs).toBe(1000);
    expect(view.error).toBe('boom');
  });

  it('handles JSON helpers safely', () => {
    expect(prettyJsonSafe('{"a":1}')).toContain('"a": 1');
    expect(prettyJsonSafe('not-json')).toBe('not-json');
    expect(parseJsonArray('[1,2]')).toEqual([1, 2]);
    expect(parseJsonArray('{}')).toEqual([]);
    expect(parseJsonArray(undefined)).toEqual([]);
  });

  it('detects succeeded state case-insensitively', () => {
    expect(isSucceededState('SUCCEEDED')).toBe(true);
    expect(isSucceededState('succeeded')).toBe(true);
    expect(isSucceededState('failed')).toBe(false);
  });

  it('builds stable sub-agent keys', () => {
    expect(subAgentMemberKey({ refSubAgentRunId: 7, seq: 1 })).toBe('7');
    expect(subAgentMemberKey({ seq: 3 })).toBe('3');
    expect(parallelGroupKey({ kind: 'subagent' })).toBeNull();
    expect(
      parallelGroupKey({ kind: 'subagent', content: { parentToolCallId: 9 } }),
    ).toBe('9');
  });
});

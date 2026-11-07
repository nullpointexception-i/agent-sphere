import { describe, expect, it } from 'vitest';
import { clarificationKey, mergeTimeline } from './timeline';
import type { TimelineRow } from './types';

/**
 * Regression: when the same run is asked follow-up questions over multiple rounds, the second-round clarification must stay PENDING.
 *
 * The backend's resumeFromClarification reuses the same runId to continue while the run is AWAITING_USER
 * (it does not create a new run), and each follow-up round has a different clarificationId but shares the runId. The previous local optimistic guard
 * keyed only by runId, so the second round's PENDING was rewritten to ANSWERED - the options and input box did not render,
 * showing as "already answered", and only came back after a refresh rebuilt the Ref.
 */
const clarRow = (seq: number, clarificationId: string, state = 'PENDING'): TimelineRow => ({
  seq,
  runId: 42,
  kind: 'clarification',
  state,
  content: { clarificationId, title: '确认范围', options: '["A","B"]' },
});

describe('clarificationKey', () => {
  it('同一 runId 不同 clarificationId 得到不同键', () => {
    expect(clarificationKey(42, 'c1')).not.toBe(clarificationKey(42, 'c2'));
  });

  it('相同输入得到相同键（乐观态可被精确回滚）', () => {
    expect(clarificationKey(42, 'c1')).toBe(clarificationKey(42, 'c1'));
  });

  it('id 缺失时返回 null，调用方据此放弃保护', () => {
    expect(clarificationKey(42, undefined)).toBeNull();
    expect(clarificationKey(42, '')).toBeNull();
    expect(clarificationKey(undefined, 'c1')).toBeNull();
  });
});

describe('mergeTimeline assistant 流式正文保护', () => {
  const assistant = (
    seq: number,
    state: string,
    text: string,
    thinking?: string,
  ): TimelineRow => ({
    seq,
    runId: 42,
    kind: 'assistant',
    state,
    content: { text, thinking },
  });

  it('RUNNING 的空权威行不覆盖本地已流出的正文/推理', () => {
    const merged = mergeTimeline(
      [assistant(20, 'RUNNING', '完整回复', '思考过程')],
      [assistant(20, 'RUNNING', '')],
    );
    expect(merged[0].content.text).toBe('完整回复');
    expect(merged[0].content.thinking).toBe('思考过程');
  });

  it('终态行允许覆盖（完成/失败以服务端为准）', () => {
    const merged = mergeTimeline(
      [assistant(20, 'RUNNING', '半截回复')],
      [assistant(20, 'FAILED', '')],
    );
    expect(merged[0].state).toBe('FAILED');
    expect(merged[0].content.text).toBe('');
  });

  it('权威行有正文时正常覆盖', () => {
    const merged = mergeTimeline(
      [assistant(20, 'RUNNING', '旧正文')],
      [assistant(20, 'RUNNING', '新正文')],
    );
    expect(merged[0].content.text).toBe('新正文');
  });
});

describe('mergeTimeline 澄清乐观态保护', () => {
  it('★回归：同 run 续跑的第二轮澄清不被改写成 ANSWERED', () => {
    const answered = new Set([clarificationKey(42, 'c1') as string]);
    const rows = [clarRow(11, 'c2')];
    const merged = mergeTimeline([], rows, answered);
    expect(merged[0].state).toBe('PENDING');
  });

  it('已本地应答的那一轮仍受保护（服务端旧 PENDING 不回退）', () => {
    const answered = new Set([clarificationKey(42, 'c1') as string]);
    const merged = mergeTimeline([], [clarRow(10, 'c1')], answered);
    expect(merged[0].state).toBe('ANSWERED');
  });

  it('同一批里两轮澄清：仅已应答的那轮被改写', () => {
    const answered = new Set([clarificationKey(42, 'c1') as string]);
    const merged = mergeTimeline([], [clarRow(10, 'c1'), clarRow(11, 'c2')], answered);
    expect(merged.find((r) => r.seq === 10)?.state).toBe('ANSWERED');
    expect(merged.find((r) => r.seq === 11)?.state).toBe('PENDING');
  });

  it('缺 clarificationId 时不拦截，采信服务端状态', () => {
    // A Set of runIds only (old key shape) must not affect the new row
    const legacy = new Set(['42']);
    const merged = mergeTimeline([], [clarRow(11, 'c2')], legacy);
    expect(merged[0].state).toBe('PENDING');
  });

  it('无保护集合时按服务端原样合并', () => {
    const merged = mergeTimeline([], [clarRow(11, 'c2')], undefined);
    expect(merged[0].state).toBe('PENDING');
  });

  it('非澄清行不受影响', () => {
    const answered = new Set([clarificationKey(42, 'c1') as string]);
    const merged = mergeTimeline(
      [],
      [{ seq: 12, runId: 42, kind: 'assistant', state: 'RUNNING', content: { text: 'x' } }],
      answered,
    );
    expect(merged[0].state).toBe('RUNNING');
  });
});

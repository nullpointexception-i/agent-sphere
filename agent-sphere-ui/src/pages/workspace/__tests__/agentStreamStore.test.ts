import type { ImStreamPayload } from '@/hooks/useReconnectingSse';
import {
  applyAgentStreamEvent,
  beginAgentStream,
  failAgentStream,
  finalizeAgentStream,
  peekAgentStream,
  resetAgentStream,
} from '../agentStreamStore';

const CONVERSATION = 44;

function streamEvent(patch: Partial<ImStreamPayload>): ImStreamPayload {
  return { conversationId: CONVERSATION, ...patch };
}

describe('agentStreamStore', () => {
  beforeEach(() => {
    resetAgentStream(CONVERSATION);
  });

  it('starts in waiting phase after the user sends', () => {
    beginAgentStream(CONVERSATION);

    expect(peekAgentStream(CONVERSATION)?.phase).toBe('waiting');
  });

  it('accumulates thinking and reply deltas in order', () => {
    beginAgentStream(CONVERSATION);

    applyAgentStreamEvent(
      streamEvent({ phase: 'thinking', delta: '分析', runId: 7, streamSeq: 1 }),
    );
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '你好', runId: 7, streamSeq: 2 }),
    );
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '！', runId: 7, streamSeq: 3 }),
    );

    const state = peekAgentStream(CONVERSATION);
    expect(state?.phase).toBe('reply');
    expect(state?.thinking).toBe('分析');
    expect(state?.reply).toBe('你好！');
    expect(state?.runId).toBe(7);
  });

  it('ignores stale fragments by streamSeq', () => {
    beginAgentStream(CONVERSATION);

    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '旧', runId: 7, streamSeq: 5 }),
    );
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '重复', runId: 7, streamSeq: 5 }),
    );

    expect(peekAgentStream(CONVERSATION)?.reply).toBe('旧');
  });

  it('tool pending clears the reply draft and shows tool status', () => {
    beginAgentStream(CONVERSATION);

    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '草稿', runId: 7, streamSeq: 1 }),
    );
    applyAgentStreamEvent(
      streamEvent({
        phase: 'status',
        streamStatus: 'tool_pending',
        toolName: 'dispatch_worker',
        displayName: '派发任务',
        runId: 7,
      }),
    );
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '最终', runId: 7, streamSeq: 2 }),
    );

    const state = peekAgentStream(CONVERSATION);
    expect(state?.phase).toBe('reply');
    expect(state?.reply).toBe('最终');
    expect(state?.displayName).toBe('派发任务');
  });

  it('terminal failed marks the stream failed with the error message', () => {
    beginAgentStream(CONVERSATION);

    applyAgentStreamEvent(
      streamEvent({
        phase: 'status',
        streamStatus: 'failed',
        errorMessage: '模型超时',
        runId: 7,
      }),
    );

    const state = peekAgentStream(CONVERSATION);
    expect(state?.phase).toBe('failed');
    expect(state?.errorMessage).toBe('模型超时');
  });

  it('finalized stream ignores late fragments of the same run', () => {
    beginAgentStream(CONVERSATION);

    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '正文', runId: 7, streamSeq: 1 }),
    );
    finalizeAgentStream(CONVERSATION, 12);
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '迟到', runId: 7, streamSeq: 2 }),
    );

    expect(peekAgentStream(CONVERSATION)?.reply).toBe('正文');
  });

  it('a newer run replaces the current stream', () => {
    beginAgentStream(CONVERSATION);

    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '第一轮', runId: 7, streamSeq: 1 }),
    );
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '第二轮', runId: 9, streamSeq: 1 }),
    );

    const state = peekAgentStream(CONVERSATION);
    expect(state?.runId).toBe(9);
    expect(state?.reply).toBe('');
  });

  it('reset removes the stream state', () => {
    beginAgentStream(CONVERSATION);
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: 'x', runId: 7, streamSeq: 1 }),
    );

    resetAgentStream(CONVERSATION);

    expect(peekAgentStream(CONVERSATION)).toBeUndefined();
  });

  it('fail marks the stream failed explicitly', () => {
    beginAgentStream(CONVERSATION);

    failAgentStream(CONVERSATION, '发送失败');

    expect(peekAgentStream(CONVERSATION)?.phase).toBe('failed');
    expect(peekAgentStream(CONVERSATION)?.errorMessage).toBe('发送失败');
  });

  it('ignores events without a conversation or before begin', () => {
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: 'x', runId: 7, streamSeq: 1 }),
    );
    applyAgentStreamEvent({ conversationId: 999, phase: 'reply', delta: 'y' });

    expect(peekAgentStream(CONVERSATION)).toBeUndefined();
  });
});

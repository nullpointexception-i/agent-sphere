import type { ImStreamPayload } from '@/hooks/useReconnectingSse';
import {
  appendLocalChunk,
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

  it('awaiting_user parks the bubble until the run resumes', () => {
    beginAgentStream(CONVERSATION);

    applyAgentStreamEvent(
      streamEvent({
        phase: 'status',
        streamStatus: 'awaiting_user',
        runId: 7,
      }),
    );

    expect(peekAgentStream(CONVERSATION)?.phase).toBe('awaiting');

    // User answered the clarification card; the resumed run re-emits STARTED
    // (non-fragmented status) and then continues streaming.
    applyAgentStreamEvent(
      streamEvent({ phase: 'status', streamStatus: 'started', runId: 7 }),
    );
    expect(peekAgentStream(CONVERSATION)?.phase).toBe('awaiting');

    applyAgentStreamEvent(
      streamEvent({ phase: 'thinking', delta: '继续', runId: 7, streamSeq: 2 }),
    );
    expect(peekAgentStream(CONVERSATION)?.phase).toBe('thinking');
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

  it('local fragments render before any server event arrives', () => {
    appendLocalChunk(CONVERSATION, {
      taskId: 31,
      agentId: 14,
      phase: 'reply',
      delta: '本地先渲染',
    });

    const state = peekAgentStream(CONVERSATION);
    expect(state?.phase).toBe('reply');
    expect(state?.reply).toBe('本地先渲染');
    expect(state?.taskId).toBe(31);
  });

  it('server catch-up replaces the optimistic text without rewinding', () => {
    beginAgentStream(CONVERSATION);
    appendLocalChunk(CONVERSATION, {
      taskId: 31,
      phase: 'reply',
      delta: '完整的本地回答',
    });

    // The backend relay is one batch behind: it reports the first fragment.
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '完整的', runId: 7, streamSeq: 1 }),
    );
    expect(peekAgentStream(CONVERSATION)?.reply).toBe('完整的本地回答');

    // It catches up with the full text: the authoritative copy is displayed.
    applyAgentStreamEvent(
      streamEvent({
        phase: 'reply',
        delta: '本地回答',
        runId: 7,
        streamSeq: 2,
      }),
    );
    expect(peekAgentStream(CONVERSATION)?.reply).toBe('完整的本地回答');
  });

  it('a reconnected server stream never truncates the longer local text', () => {
    beginAgentStream(CONVERSATION);
    appendLocalChunk(CONVERSATION, {
      taskId: 31,
      phase: 'reply',
      delta: '前半段后半段',
    });

    // SSE reconnects mid-run and only delivers the tail.
    applyAgentStreamEvent(
      streamEvent({ phase: 'reply', delta: '后半段', runId: 7, streamSeq: 9 }),
    );

    expect(peekAgentStream(CONVERSATION)?.reply).toBe('前半段后半段');
  });

  it('ignores local fragments after the stream is finalized', () => {
    beginAgentStream(CONVERSATION);
    appendLocalChunk(CONVERSATION, {
      taskId: 31,
      phase: 'reply',
      delta: '正文',
    });
    finalizeAgentStream(CONVERSATION, 12);

    appendLocalChunk(CONVERSATION, {
      taskId: 31,
      phase: 'reply',
      delta: '迟到',
    });

    expect(peekAgentStream(CONVERSATION)?.reply).toBe('正文');
  });

  it('a new local task replaces the previous optimistic text', () => {
    appendLocalChunk(CONVERSATION, {
      taskId: 31,
      phase: 'reply',
      delta: '旧任务',
    });
    appendLocalChunk(CONVERSATION, {
      taskId: 32,
      phase: 'reply',
      delta: '新任务',
    });

    const state = peekAgentStream(CONVERSATION);
    expect(state?.taskId).toBe(32);
    expect(state?.reply).toBe('新任务');
  });
});

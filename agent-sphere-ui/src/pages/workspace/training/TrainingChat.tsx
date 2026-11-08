import { SendOutlined, StopOutlined } from '@ant-design/icons';
import { App, Button, Input, Spin } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { API_PATHS } from '@/constants/endpoints';
import { TIMING } from '@/constants/timing';
import { useReconnectingSse } from '@/hooks/useReconnectingSse';
import { agentApi } from '@/services/agentSphere/api';
import { superAgentApi } from '@/services/agentSphere/superAgent';
import TimelineList from '../../chat/components/chat/TimelineList';
import ToolApprovalDrawer, {
  type ToolApprovalBatch,
} from '../../chat/components/ToolApprovalDrawer';
import { tw } from '../i18n';
import { useStyles } from './style';

interface Props {
  agentId: number;
  sessionId: number;
}

function mergeRows(previous: any[], incoming: any[]) {
  const map = new Map<number, any>();
  previous.forEach((row) => {
    map.set(Number(row.seq), row);
  });
  incoming.forEach((row) => {
    map.set(Number(row.seq), row);
  });
  return [...map.values()].sort((a, b) => Number(a.seq) - Number(b.seq));
}

export function TrainingChat({ agentId, sessionId }: Props) {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const [rows, setRows] = useState<any[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [text, setText] = useState('');
  const [running, setRunning] = useState(false);
  const [sending, setSending] = useState(false);
  const [approvalBatch, setApprovalBatch] = useState<ToolApprovalBatch | null>(
    null,
  );
  const [approvalSubmitting, setApprovalSubmitting] = useState(false);
  const [clarificationPending, setClarificationPending] = useState(false);
  const oldestSeqRef = useRef<number | null>(null);
  const newestSeqRef = useRef<number | null>(null);
  const refreshTimerRef = useRef<number | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const stickToBottomRef = useRef(true);

  const bumpCursors = (incoming: any[]) => {
    if (incoming.length === 0) return;
    const seqs = incoming.map((row) => Number(row.seq)).filter(Number.isFinite);
    if (seqs.length === 0) return;
    const min = Math.min(...seqs);
    const max = Math.max(...seqs);
    oldestSeqRef.current =
      oldestSeqRef.current == null ? min : Math.min(oldestSeqRef.current, min);
    newestSeqRef.current =
      newestSeqRef.current == null ? max : Math.max(newestSeqRef.current, max);
  };

  const refreshState = useCallback(async () => {
    try {
      const state = await agentApi.sessions.getRuntimeState(sessionId);
      const active =
        state?.runStatus === 'RUNNING' || state?.runStatus === 'WAITING';
      setRunning(Boolean(active));
      setClarificationPending(Number(state?.pendingClarifications || 0) > 0);
    } catch {
      // Transient state failure must not lock the composer.
    }
  }, [sessionId]);

  const refreshTail = useCallback(async () => {
    try {
      const page = await agentApi.sessions.getTimeline(sessionId, {
        limit: 40,
      });
      const incoming = Array.isArray(page?.rows) ? page.rows : [];
      if (incoming.length) {
        setRows((previous) => mergeRows(previous, incoming));
        bumpCursors(incoming);
      }
      setHasMore(Boolean(page?.hasMore));
    } catch {
      // ignore
    }
  }, [sessionId]);

  const refreshLatest = useCallback(async () => {
    try {
      const page = await agentApi.sessions.getTimeline(
        sessionId,
        newestSeqRef.current != null
          ? { afterSeq: newestSeqRef.current, limit: 20 }
          : { limit: 20 },
      );
      const incoming = Array.isArray(page?.rows) ? page.rows : [];
      if (incoming.length) {
        setRows((previous) => mergeRows(previous, incoming));
        bumpCursors(incoming);
      }
      if (page?.hasMore) setHasMore(true);
    } catch {
      // ignore
    }
  }, [sessionId]);

  const loadInitial = useCallback(async () => {
    setLoading(true);
    try {
      const page = await agentApi.sessions.getTimeline(sessionId, {
        limit: 30,
      });
      const incoming = Array.isArray(page?.rows) ? page.rows : [];
      setRows((previous) => mergeRows(previous, incoming));
      oldestSeqRef.current = null;
      newestSeqRef.current = null;
      bumpCursors(incoming);
      setHasMore(Boolean(page?.hasMore));
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [sessionId]);

  const refreshApproval = useCallback(async () => {
    try {
      const vo = await agentApi.toolApprovals.pending(sessionId);
      if (!vo?.approvalId || !vo?.toolCallsJson) {
        setApprovalBatch(null);
        return;
      }
      const calls = JSON.parse(vo.toolCallsJson);
      const pendingCalls = Array.isArray(calls)
        ? calls.filter((call: any) => call?.pending !== false)
        : [];
      if (pendingCalls.length === 0) {
        setApprovalBatch(null);
        return;
      }
      setApprovalBatch({
        approvalId: String(vo.approvalId),
        runId: Number(vo.runId),
        calls: pendingCalls,
      });
    } catch {
      // keep the current drawer on transient errors
    }
  }, [sessionId]);

  const scheduleRefresh = useCallback(() => {
    if (refreshTimerRef.current != null) return;
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = null;
      void refreshTail();
      void refreshState();
    }, 120);
  }, [refreshState, refreshTail]);

  useEffect(() => {
    void loadInitial();
    void refreshState();
    void refreshApproval();
    return () => {
      if (refreshTimerRef.current != null) {
        window.clearTimeout(refreshTimerRef.current);
      }
    };
  }, [loadInitial, refreshApproval, refreshState]);

  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => {
      void refreshState();
      void refreshLatest();
    }, TIMING.APPROVAL_POLL_MS);
    return () => window.clearInterval(timer);
  }, [refreshLatest, refreshState, running]);

  useEffect(() => {
    if (!stickToBottomRef.current) return;
    const element = scrollRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [rows]);

  useReconnectingSse(API_PATHS.RUNTIME_STREAM(sessionId), (type, event) => {
    const data: any = event;
    if (type === 'heartbeat') return;
    if (
      data?.seq != null &&
      data?.kind === 'assistant' &&
      data?.response &&
      (type === 'content_token' || type === 'reasoning_token')
    ) {
      const seq = Number(data.seq);
      const field = type === 'content_token' ? 'reply' : 'thinking';
      setRows((previous) => {
        let found = false;
        const next = previous.map((row) => {
          if (Number(row.seq) !== seq) return row;
          found = true;
          return {
            ...row,
            content: {
              ...(row.content ?? {}),
              [field]: (row.content?.[field] ?? '') + data.response,
            },
          };
        });
        if (!found) {
          next.push({
            seq,
            kind: 'assistant',
            status: 'RUNNING',
            content: { [field]: data.response },
          });
          next.sort((a, b) => Number(a.seq) - Number(b.seq));
        }
        return next;
      });
      return;
    }
    if (type === 'TOOL_APPROVAL_PENDING') {
      void refreshApproval();
      scheduleRefresh();
      return;
    }
    if (
      type === 'TOOL_APPROVAL_RESPONDED' ||
      type === 'TOOL_APPROVAL_DISMISSED'
    ) {
      setApprovalBatch((previous) =>
        previous && previous.approvalId === String(data?.approvalId)
          ? null
          : previous,
      );
      void refreshApproval();
      scheduleRefresh();
      return;
    }
    scheduleRefresh();
  });

  const loadOlder = async () => {
    if (oldestSeqRef.current == null) return;
    try {
      const page = await agentApi.sessions.getTimeline(sessionId, {
        beforeSeq: oldestSeqRef.current,
        limit: 20,
      });
      const incoming = Array.isArray(page?.rows) ? page.rows : [];
      if (incoming.length) {
        setRows((previous) => mergeRows(previous, incoming));
        bumpCursors(incoming);
      }
      setHasMore(Boolean(page?.hasMore));
    } catch {
      setHasMore(false);
    }
  };

  const send = async () => {
    const value = text.trim();
    if (!value || sending || running || approvalBatch || clarificationPending) {
      return;
    }
    setSending(true);
    setRunning(true);
    stickToBottomRef.current = true;
    setText('');
    try {
      await superAgentApi.sendTrainingMessage(agentId, sessionId, {
        message: value,
      });
      await refreshState();
      await refreshTail();
    } catch {
      message.error(tw('training.sendFailed', 'Failed to send message'));
      setText(value);
      setRunning(false);
    } finally {
      setSending(false);
    }
  };

  const stop = async () => {
    try {
      await agentApi.runs.sessionStop(sessionId);
      setRunning(false);
      await refreshState();
    } catch {
      message.error(tw('training.stopFailed', 'Failed to stop the run'));
    }
  };

  const respondClarify = async (row: any, response: string) => {
    try {
      await agentApi.sessions.clarify(
        sessionId,
        Number(row.runId),
        response,
        row.clarificationId,
      );
      await refreshTail();
      await refreshState();
    } catch {
      message.error(tw('training.replyFailed', 'Failed to submit the answer'));
    }
  };

  const cancelClarification = async (row: any) => {
    try {
      if (row?.runId && row?.clarificationId) {
        await agentApi.sessions.clarify(
          sessionId,
          Number(row.runId),
          '__cancel__',
          row.clarificationId,
        );
      }
      await agentApi.runs.sessionStop(sessionId);
      await refreshState();
    } catch {
      // ignore
    }
  };

  const resolveApproval = async (
    decisions: Array<{ callId: string; decision: string }>,
  ) => {
    if (!approvalBatch) return;
    setApprovalSubmitting(true);
    try {
      await agentApi.toolApprovals.resolve(
        sessionId,
        approvalBatch.runId,
        approvalBatch.approvalId,
        decisions,
      );
      setApprovalBatch(null);
      await refreshTail();
      await refreshState();
    } catch {
      message.error(tw('training.approvalFailed', 'Failed to submit approval'));
    } finally {
      setApprovalSubmitting(false);
    }
  };

  return (
    <div className={styles.chat}>
      {approvalBatch ? (
        <div className={styles.notice}>
          {tw(
            'training.approvalNotice',
            'The Worker is waiting for an L2 tool approval.',
          )}
        </div>
      ) : null}
      {clarificationPending ? (
        <div className={styles.notice}>
          {tw(
            'training.clarificationNotice',
            'The Worker is waiting for your answer in the conversation above.',
          )}
        </div>
      ) : null}
      <div
        ref={scrollRef}
        className={styles.scroll}
        onScroll={(event) => {
          const element = event.currentTarget;
          stickToBottomRef.current =
            element.scrollHeight - element.scrollTop - element.clientHeight <
            96;
        }}
      >
        <Spin spinning={loading}>
          {rows.length === 0 && !loading ? (
            <div className={styles.empty}>
              {tw(
                'training.empty',
                'Talk directly to the Worker. Ask it to demonstrate a capability, then adjust the bindings on the right.',
              )}
            </div>
          ) : (
            <TimelineList
              rows={rows}
              hasMore={hasMore}
              sessionId={sessionId}
              onLoadOlder={loadOlder}
              onRespondClarify={respondClarify}
              onCancelClarification={cancelClarification}
              loadSubAgentSteps={async () => []}
            />
          )}
        </Spin>
      </div>
      <div className={styles.composer}>
        <Input.TextArea
          value={text}
          onChange={(event) => setText(event.target.value)}
          autoSize={{ minRows: 2, maxRows: 5 }}
          maxLength={5000}
          placeholder={tw(
            'training.placeholder',
            'Describe what you want the Worker to learn or test…',
          )}
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              void send();
            }
          }}
        />
        {running ? (
          <Button danger icon={<StopOutlined />} onClick={() => void stop()}>
            {tw('training.stop', 'Stop')}
          </Button>
        ) : (
          <Button
            type="primary"
            icon={<SendOutlined />}
            loading={sending}
            disabled={!text.trim() || !!approvalBatch || clarificationPending}
            onClick={() => void send()}
          >
            {tw('training.send', 'Send')}
          </Button>
        )}
      </div>
      <ToolApprovalDrawer
        open={!!approvalBatch}
        batch={approvalBatch}
        submitting={approvalSubmitting}
        onResolve={(decisions) => void resolveApproval(decisions)}
      />
    </div>
  );
}

import { useState } from 'react';
import type { SessionInputDecision } from '@/services/agentSphere/im';
import { tw } from './i18n';
import { useStyles } from './style';

export interface AgentInputToolCall {
  callId: string;
  toolName?: string;
  displayName?: string;
  action?: string;
  arguments?: unknown;
  approvalScope?: string;
}

/** Rendered ROUTER_CLARIFICATION / ROUTER_APPROVAL card view (from message content). */
export interface AgentInputCardView {
  cardType?: string;
  title?: string;
  description?: string;
  status?: string;
  inputId?: string;
  multiple?: boolean;
  options?: string;
  toolCallsJson?: string;
}

export const ROUTER_CLARIFICATION = 'ROUTER_CLARIFICATION';
export const ROUTER_APPROVAL = 'ROUTER_APPROVAL';

function taskInputStatusLabel(status?: string) {
  switch (status) {
    case 'ANSWERED':
      return tw('chat.inputStatus.answered', 'Answered');
    case 'CANCELLED':
      return tw('chat.inputStatus.cancelled', 'Cancelled');
    default:
      return status || tw('chat.taskStatus.unknown', 'Unknown');
  }
}

function parseList<T>(json?: string): T[] {
  if (!json) return [];
  try {
    const value = JSON.parse(json);
    return Array.isArray(value) ? (value as T[]) : [];
  } catch {
    return [];
  }
}

function optionText(option: unknown): string | null {
  if (typeof option === 'string') {
    return option.trim() || null;
  }
  if (option && typeof option === 'object') {
    const record = option as Record<string, unknown>;
    for (const key of ['label', 'value', 'text', 'option']) {
      if (typeof record[key] === 'string' && (record[key] as string).trim()) {
        return (record[key] as string).trim();
      }
    }
  }
  return null;
}

function toolCallName(call: AgentInputToolCall) {
  return call.displayName || call.toolName || call.callId;
}

function toolCallArgsLabel(call: AgentInputToolCall) {
  if (call.arguments === undefined || call.arguments === null) return '';
  const text =
    typeof call.arguments === 'string'
      ? call.arguments
      : JSON.stringify(call.arguments);
  return text.length > 200 ? `${text.slice(0, 200)}…` : text;
}

const DECISIONS = ['DENY', 'ALLOW_ONCE', 'ALLOW_RUN', 'ALLOW_SESSION'] as const;

function decisionLabel(decision: string) {
  switch (decision) {
    case 'DENY':
      return tw('chat.inputDecision.deny', 'Deny');
    case 'ALLOW_ONCE':
      return tw('chat.inputDecision.allowOnce', 'Allow once');
    case 'ALLOW_RUN':
      return tw('chat.inputDecision.allowRun', 'This run');
    case 'ALLOW_SESSION':
      return tw('chat.inputDecision.allowSession', 'This session');
    default:
      return decision;
  }
}

/**
 * Router parked-input card: clarification options are answered as plain chat
 * text (the backend consumes it and resumes the parked run); approval calls are
 * decided per call and submitted through the approval endpoint.
 */
export function AgentInputCard({
  card,
  conversationId,
  onReplyText,
  submitting,
  onApprove,
}: {
  card: AgentInputCardView;
  conversationId: number;
  onReplyText?: (text: string) => void;
  submitting?: boolean;
  onApprove?: (
    conversationId: number,
    inputId: string,
    decisions: SessionInputDecision[],
  ) => Promise<boolean>;
}) {
  const { styles } = useStyles();
  const pending = card.status === 'PENDING';
  const clarification = card.cardType === ROUTER_CLARIFICATION;
  const approval = card.cardType === ROUTER_APPROVAL;
  const [picked, setPicked] = useState<number[]>([]);
  const [decisions, setDecisions] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  if (!pending || !conversationId) {
    return (
      <div className={styles.taskCard}>
        <div className={styles.taskCardTop}>
          <span className={styles.taskCardTitle}>
            {card.title ||
              tw('chat.inputFallback', 'Your avatar needs your input')}
          </span>
          <span
            className={`${styles.taskCardStatus} ${
              card.status === 'ANSWERED'
                ? styles.taskCardStatusDone
                : styles.taskCardStatusFailed
            }`}
          >
            {taskInputStatusLabel(card.status)}
          </span>
        </div>
        {card.description ? (
          <div className={styles.taskCardSummary}>{card.description}</div>
        ) : null}
      </div>
    );
  }

  const options = Array.from(
    new Set(
      parseList<unknown>(card.options)
        .map((option) => optionText(option))
        .filter((text): text is string => Boolean(text)),
    ),
  ).slice(0, 12);
  const toolCalls = parseList<AgentInputToolCall>(card.toolCallsJson).slice(
    0,
    20,
  );

  const repliesDisabled = submitting || busy || !onReplyText;

  const submitOption = (text: string) => {
    if (repliesDisabled) return;
    onReplyText?.(text);
  };

  const toggleMultipleOption = (index: number) => {
    if (repliesDisabled) return;
    setPicked((previous) =>
      previous.includes(index)
        ? previous.filter((value) => value !== index)
        : [...previous, index],
    );
  };

  const submitApproval = async () => {
    if (!approval || !card.inputId || !onApprove || busy) return;
    const list: SessionInputDecision[] = toolCalls
      .map((call) => ({
        callId: call.callId,
        decision: decisions[call.callId],
      }))
      .filter((row) => Boolean(row.decision));
    if (list.length !== toolCalls.length) return;
    setBusy(true);
    try {
      // On success the button collapses via the authoritative PENDING→non-PENDING update; this only
      // guards against duplicate requests while in flight.
      await onApprove(conversationId, card.inputId, list);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={styles.taskCard}>
      <div className={styles.taskCardTop}>
        <span className={styles.taskCardTitle}>
          {card.title ||
            tw('chat.inputFallback', 'Your avatar needs your input')}
        </span>
        <span
          className={`${styles.taskCardStatus} ${styles.taskCardStatusRunning}`}
        >
          {tw('chat.taskStatus.pending', 'Awaiting reply')}
        </span>
      </div>
      {card.description ? (
        <div className={styles.agentInputPrompt}>{card.description}</div>
      ) : null}
      {clarification ? (
        <>
          <div className={styles.agentInputOptions}>
            {options.map((option, index) => {
              const active = card.multiple && picked.includes(index);
              return (
                <button
                  key={option}
                  type="button"
                  className={`${styles.agentInputOption} ${
                    active ? styles.agentInputOptionActive : ''
                  } ${repliesDisabled ? styles.agentInputOptionDisabled : ''}`}
                  onClick={() => {
                    if (card.multiple) {
                      toggleMultipleOption(index);
                    } else {
                      submitOption(option);
                    }
                  }}
                >
                  {option}
                </button>
              );
            })}
          </div>
          {card.multiple && picked.length > 0 ? (
            <button
              type="button"
              className={`${styles.agentInputSubmit} ${
                repliesDisabled ? styles.agentInputSubmitDisabled : ''
              }`}
              onClick={() =>
                submitOption(picked.map((index) => options[index]).join(', '))
              }
            >
              {tw('chat.inputSubmitAnswer', 'Submit answer')}
            </button>
          ) : null}
        </>
      ) : null}
      {approval ? (
        toolCalls.length > 0 ? (
          <>
            {toolCalls.map((call) => (
              <div key={call.callId} className={styles.agentInputToolCall}>
                <div className={styles.agentInputToolName}>
                  {toolCallName(call)}
                  {call.action ? ` · ${call.action}` : ''}
                </div>
                {toolCallArgsLabel(call) ? (
                  <div className={styles.agentInputToolArgs}>
                    {toolCallArgsLabel(call)}
                  </div>
                ) : null}
                <div className={styles.agentInputDecisions}>
                  {DECISIONS.map((decision) => {
                    const active = decisions[call.callId] === decision;
                    return (
                      <button
                        key={decision}
                        type="button"
                        disabled={busy}
                        className={`${styles.agentInputDecision} ${
                          active ? styles.agentInputDecisionActive : ''
                        } ${
                          decision === 'DENY'
                            ? styles.agentInputDecisionDeny
                            : ''
                        }`}
                        onClick={() =>
                          setDecisions((previous) => {
                            const next = { ...previous };
                            next[call.callId] = decision;
                            return next;
                          })
                        }
                      >
                        {decisionLabel(decision)}
                      </button>
                    );
                  })}
                </div>
              </div>
            ))}
            <button
              type="button"
              disabled={busy}
              className={`${styles.agentInputSubmit} ${
                toolCalls.some((call) => !decisions[call.callId]) || busy
                  ? styles.agentInputSubmitDisabled
                  : ''
              }`}
              onClick={submitApproval}
            >
              {busy
                ? tw('chat.inputSubmitting', 'Submitting…')
                : tw('chat.inputSubmitDecisions', 'Submit decisions')}
            </button>
          </>
        ) : (
          <div className={styles.agentInputDoneHint}>
            {tw(
              'chat.inputToolCallsMissing',
              'Tool call details unavailable; handle it in the console',
            )}
          </div>
        )
      ) : null}
    </div>
  );
}

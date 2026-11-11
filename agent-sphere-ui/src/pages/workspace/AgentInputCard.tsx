import { Input } from 'antd';
import { useState } from 'react';
import { TIMELINE_STATE } from '@/constants/timeline';
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
  /** Resolved cards: submitted decisions (approval) or answer (clarification). */
  decisionsJson?: string;
  /** Resolved cards: username that answered/approved. */
  respondedBy?: string;
  /** Resolved cards: answer/approval time. */
  respondedAt?: string;
}

export interface AgentClarificationAnswer {
  mode: 'SINGLE_OPTION' | 'MULTI_OPTION' | 'FREE_TEXT';
  selectedOptions?: string[];
  text?: string;
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

/** Resolved approval cards: callId → submitted decision. */
function parseDecisions(json?: string): Record<string, string> {
  const map: Record<string, string> = {};
  for (const row of parseList<{ callId?: string; decision?: string }>(json)) {
    if (row?.callId && row?.decision) {
      map[row.callId] = row.decision;
    }
  }
  return map;
}

/** Resolved clarification cards: the answer captured on the card. */
function parseAnswer(json?: string): string | null {
  if (!json) return null;
  try {
    const value = JSON.parse(json) as { answer?: unknown };
    return typeof value?.answer === 'string' ? value.answer : null;
  } catch {
    return null;
  }
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
 * Router parked-input card. Clarifications are answered only inside the card: options select,
 * free text is typed here, and an explicit confirm submits. Approvals keep their per-call flow.
 */
export function AgentInputCard({
  card,
  conversationId,
  onAnswer,
  canView = true,
  canAnswer = true,
  deniedReason,
  onApprove,
}: {
  card: AgentInputCardView;
  conversationId: number;
  onAnswer?: (
    inputId: string,
    payload: AgentClarificationAnswer,
  ) => Promise<boolean>;
  canView?: boolean;
  canAnswer?: boolean;
  deniedReason?: string;
  onApprove?: (
    conversationId: number,
    inputId: string,
    decisions: SessionInputDecision[],
  ) => Promise<boolean>;
}) {
  const { styles } = useStyles();
  const pending = card.status === TIMELINE_STATE.PENDING;
  const clarification = card.cardType === ROUTER_CLARIFICATION;
  const approval = card.cardType === ROUTER_APPROVAL;
  const [picked, setPicked] = useState<number[]>([]);
  const [selectedOption, setSelectedOption] = useState<number | null>(null);
  const [freeText, setFreeText] = useState('');
  const [decisions, setDecisions] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  if (clarification && !canView) {
    return (
      <div className={styles.taskCard}>
        <div className={styles.taskCardTop}>
          <span className={styles.taskCardTitle}>
            {tw(
              'chat.clarificationHidden',
              'A clarification is visible to its responders only',
            )}
          </span>
          <span className={styles.taskCardStatus}>
            {tw('chat.taskStatus.pending', 'Awaiting reply')}
          </span>
        </div>
        <div className={styles.agentInputDoneHint}>
          {tw(
            'chat.clarificationHiddenHint',
            'Only the asker, the avatar owner and group admins can see and answer it.',
          )}
        </div>
      </div>
    );
  }

  if (!pending || !conversationId) {
    const resolvedCalls = parseList<AgentInputToolCall>(
      card.toolCallsJson,
    ).slice(0, 20);
    const resolvedDecisions = parseDecisions(card.decisionsJson);
    const resolvedAnswer = parseAnswer(card.decisionsJson);
    return (
      <div className={styles.taskCard}>
        <div className={styles.taskCardTop}>
          <span className={styles.taskCardTitle}>
            {card.title ||
              tw('chat.inputFallback', 'Your avatar needs your input')}
          </span>
          <span
            className={`${styles.taskCardStatus} ${
              card.status === TIMELINE_STATE.ANSWERED
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
        {approval && resolvedCalls.length > 0
          ? resolvedCalls.map((call) => {
              const decision = resolvedDecisions[call.callId];
              return (
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
                  {decision ? (
                    <div
                      style={{ marginTop: 4, fontSize: 12, color: '#3f8cff' }}
                    >
                      {tw('chat.inputDecisionTaken', 'Chosen: {decision}', {
                        decision: decisionLabel(decision),
                      })}
                    </div>
                  ) : null}
                </div>
              );
            })
          : null}
        {clarification && resolvedAnswer ? (
          <div style={{ marginTop: 6, fontSize: 12, color: '#3f8cff' }}>
            {tw('chat.inputAnswerTaken', 'Answer: {answer}', {
              answer: card.respondedBy
                ? `${resolvedAnswer} · ${card.respondedBy}`
                : resolvedAnswer,
            })}
            {card.respondedAt ? ` · ${card.respondedAt}` : ''}
          </div>
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
  const disabled = busy || !canAnswer;
  const freeTextMode = options.length === 0;
  const multiple = Boolean(card.multiple);
  const valid = freeTextMode
    ? freeText.trim().length > 0
    : multiple
      ? picked.length > 0
      : selectedOption !== null;

  const toggleMultipleOption = (index: number) => {
    if (disabled) return;
    setPicked((previous) =>
      previous.includes(index)
        ? previous.filter((value) => value !== index)
        : [...previous, index],
    );
  };

  const submitClarification = async () => {
    if (!valid || disabled || !card.inputId || !onAnswer) return;
    setBusy(true);
    try {
      const payload: AgentClarificationAnswer = freeTextMode
        ? { mode: 'FREE_TEXT', text: freeText.trim() }
        : multiple
          ? {
              mode: 'MULTI_OPTION',
              selectedOptions: picked.map((index) => options[index]),
            }
          : {
              mode: 'SINGLE_OPTION',
              selectedOptions: [options[selectedOption as number]],
            };
      await onAnswer(card.inputId, payload);
    } finally {
      setBusy(false);
    }
  };

  const submitApproval = async () => {
    if (!approval || !canAnswer || !card.inputId || !onApprove || busy) return;
    const list: SessionInputDecision[] = toolCalls
      .map((call) => ({
        callId: call.callId,
        decision: decisions[call.callId],
      }))
      .filter((row) => Boolean(row.decision));
    if (list.length !== toolCalls.length) return;
    setBusy(true);
    try {
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
      {approval && !canAnswer ? (
        <div className={styles.agentInputDenied}>
          {tw(
            'chat.approvalOwnerOnly',
            'Only the avatar owner can approve this action',
          )}
        </div>
      ) : null}
      {clarification ? (
        <>
          {freeTextMode ? (
            <Input.TextArea
              value={freeText}
              rows={2}
              maxLength={8000}
              disabled={disabled}
              placeholder={tw('chat.clarificationInput', 'Type your answer…')}
              onChange={(event) => setFreeText(event.target.value)}
              style={{ marginTop: 8 }}
            />
          ) : (
            <div className={styles.agentInputOptions}>
              {options.map((option, index) => {
                const active = multiple
                  ? picked.includes(index)
                  : selectedOption === index;
                return (
                  <button
                    key={option}
                    type="button"
                    className={`${styles.agentInputOption} ${
                      active ? styles.agentInputOptionActive : ''
                    } ${disabled ? styles.agentInputOptionDisabled : ''}`}
                    onClick={() => {
                      if (disabled) return;
                      if (multiple) {
                        toggleMultipleOption(index);
                      } else {
                        setSelectedOption(index);
                      }
                    }}
                  >
                    {option}
                  </button>
                );
              })}
            </div>
          )}
          {!canAnswer ? (
            <div className={styles.agentInputDoneHint}>
              {deniedReason ||
                tw(
                  'chat.clarificationNoPermission',
                  'Only the asker, the avatar owner and group admins can answer.',
                )}
            </div>
          ) : (
            <button
              type="button"
              disabled={!valid || disabled}
              className={`${styles.agentInputSubmit} ${
                !valid || disabled ? styles.agentInputSubmitDisabled : ''
              }`}
              onClick={submitClarification}
            >
              {busy
                ? tw('chat.inputSubmitting', 'Submitting…')
                : tw('chat.inputSubmitAnswer', 'Submit answer')}
            </button>
          )}
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
                        disabled={disabled}
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
              disabled={disabled}
              className={`${styles.agentInputSubmit} ${
                toolCalls.some((call) => !decisions[call.callId]) ||
                busy ||
                !canAnswer
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

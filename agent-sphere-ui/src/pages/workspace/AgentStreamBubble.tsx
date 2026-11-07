import { Avatar } from 'antd';
import { memo, useEffect, useRef, useState } from 'react';
import type { ImParticipant } from '@/services/agentSphere/im';
import type { AgentStreamState } from './agentStreamStore';
import { tw } from './i18n';
import { MessageMarkdown } from './MessageMarkdown';
import { avatarStyle, initials } from './shared';
import { useStyles } from './style';

function streamStatusLabel(state: AgentStreamState) {
  if (state.phase === 'failed') {
    return state.errorMessage
      ? `${tw('chat.agentStreamFailed', 'Reply failed')} · ${state.errorMessage}`
      : tw('chat.agentStreamFailed', 'Reply failed');
  }
  if (state.phase === 'waiting') {
    return tw('chat.agentWaiting', 'Processing…');
  }
  if (state.phase === 'tool') {
    const tool = state.displayName || state.toolName || '';
    return tool
      ? tw('chat.agentToolCalling', 'Calling {tool}', { tool })
      : tw('chat.agentWaiting', 'Processing…');
  }
  if (state.phase === 'thinking') {
    return tw('chat.agentThinking', 'Thinking…');
  }
  if (state.phase === 'awaiting') {
    return tw('chat.agentAwaitingUser', 'Waiting for your reply');
  }
  if (state.phase === 'reply') {
    return tw('chat.agentReplying', 'Replying');
  }
  return tw('chat.agentDone', 'Done');
}

/**
 * Reasoning chip in the title line: streams the latest thinking fragment as a
 * scrolling pill (tail keeps scrolling in); clicking expands a floating panel
 * with the full reasoning text, so the message flow layout never shifts.
 */
const ThinkingChip = memo(function ThinkingChip({
  thinking,
  live,
  seconds,
}: {
  thinking: string;
  live: boolean;
  seconds?: number | null;
}) {
  const { styles } = useStyles();
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLSpanElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  // While live, keep the tail of the reasoning visible inside the pill.
  useEffect(() => {
    if (!live) return;
    const box = boxRef.current;
    if (box) {
      box.scrollLeft = box.scrollWidth;
    }
  }, [thinking, live]);

  // Floating panel follows the stream while the reasoning is running.
  useEffect(() => {
    if (!open || !live) return;
    const panel = panelRef.current;
    if (panel) {
      panel.scrollTop = panel.scrollHeight;
    }
  }, [open, live, thinking]);

  return (
    <span className={styles.agentStreamChipWrap}>
      <button
        type="button"
        className={styles.agentStreamChip}
        onClick={() => setOpen((previous) => !previous)}
        aria-expanded={open}
      >
        <span ref={boxRef} className={styles.agentStreamChipText}>
          {live
            ? thinking
            : tw('chat.agentThoughtFor', 'Thought for {seconds}s', {
                seconds: seconds ?? 0,
              })}
        </span>
      </button>
      {open ? (
        <div
          ref={panelRef}
          role="dialog"
          className={
            live
              ? `${styles.agentStreamChipPanel} ${styles.agentStreamChipPanelLive}`
              : styles.agentStreamChipPanel
          }
        >
          <pre className={styles.agentStreamChipPanelText}>{thinking}</pre>
        </div>
      ) : null}
    </span>
  );
});

/**
 * Ephemeral agent streaming bubble. Layout mirrors an incoming message row:
 * typing-dots placeholder bubble while reasoning runs, reasoning chip in the
 * title line, then the reply streams as markdown into the white bubble and is
 * finally replaced by the authoritative message.
 */
export const AgentStreamBubble = memo(function AgentStreamBubble({
  state,
  agent,
  hidden,
}: {
  state: AgentStreamState;
  agent?: ImParticipant;
  /**
   * True while the placeholder must not render yet: the waiting bubble is
   * gated on the user's own message being visible in the list first.
   */
  hidden?: boolean;
}) {
  const { styles } = useStyles();
  const [elapsed, setElapsed] = useState(0);
  const [thinkingSeconds, setThinkingSeconds] = useState<number | null>(null);

  useEffect(() => {
    if (state.finalized) return undefined;
    const timer = window.setInterval(() => {
      setElapsed(
        Math.max(0, Math.round((Date.now() - state.startedAt) / 1000)),
      );
    }, 1000);
    return () => window.clearInterval(timer);
  }, [state.startedAt, state.finalized]);

  useEffect(() => {
    if (state.phase === 'thinking') {
      return;
    }
    // Freeze how long reasoning took, shown later as "thought for Xs".
    setThinkingSeconds((previous) => {
      if (previous !== null) {
        return previous;
      }
      return Math.max(0, Math.round((Date.now() - state.startedAt) / 1000));
    });
  }, [state.phase, state.startedAt]);

  if (hidden) {
    return null;
  }
  if (state.phase === 'done' && !state.thinking) {
    return null;
  }

  const agentName = agent?.displayName || tw('chat.agent', 'Digital avatar');
  const streaming = !state.finalized;
  const liveThinking = state.phase === 'thinking' && streaming;
  const showPlaceholder =
    !state.reply && state.phase !== 'failed' && state.phase !== 'done';
  const awaiting = state.phase === 'awaiting';

  return (
    <div className={styles.agentStreamRow}>
      <Avatar size={32} src={agent?.avatar} style={avatarStyle(agentName)}>
        {initials(agentName)}
      </Avatar>
      <div className={styles.agentStreamBubbleWrap}>
        <div className={`${styles.messageMeta} ${styles.agentStreamMeta}`}>
          {agentName}
          {' · '}
          {streamStatusLabel(state)}
          {streaming && elapsed > 0 ? ` · ${elapsed}s` : ''}
          {state.thinking ? (
            <ThinkingChip
              thinking={state.thinking}
              live={liveThinking}
              seconds={thinkingSeconds}
            />
          ) : null}
        </div>
        {state.reply ? (
          <div className={styles.agentStreamReplyBubble}>
            <MessageMarkdown text={state.reply} streaming={streaming} />
          </div>
        ) : null}
        {showPlaceholder ? (
          <div className={styles.agentStreamBubble}>
            {awaiting ? (
              <span className={styles.agentStreamStatus}>
                {streamStatusLabel(state)}
              </span>
            ) : (
              <span className={styles.agentStreamDots} aria-hidden>
                <span />
                <span />
                <span />
              </span>
            )}
          </div>
        ) : null}
        {state.phase === 'failed' && state.errorMessage ? (
          <div className={styles.agentStreamError}>{state.errorMessage}</div>
        ) : null}
      </div>
    </div>
  );
});

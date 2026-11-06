import { DownOutlined, UpOutlined } from '@ant-design/icons';
import { Avatar } from 'antd';
import { memo, useEffect, useState } from 'react';
import type { ImParticipant } from '@/services/agentSphere/im';
import type { AgentStreamState } from './agentStreamStore';
import { tw } from './i18n';
import { avatarStyle, initials } from './shared';
import { useStyles } from './style';

function streamStatusLabel(state: AgentStreamState) {
  if (state.phase === 'failed') {
    return state.errorMessage
      ? `${tw('chat.agentStreamFailed', '回复失败')} · ${state.errorMessage}`
      : tw('chat.agentStreamFailed', '回复失败');
  }
  if (state.phase === 'tool') {
    const tool = state.displayName || state.toolName || '';
    return tool
      ? tw('chat.agentToolCalling', '正在调用 {tool}', { tool })
      : tw('chat.agentWaiting', '正在处理…');
  }
  if (state.phase === 'thinking') {
    return tw('chat.agentThinking', '正在思考…');
  }
  if (state.phase === 'reply') {
    return tw('chat.agentReplying', '正在回复');
  }
  if (state.phase === 'done') {
    return tw('chat.agentStreamDone', '已完成，等待消息…');
  }
  return tw('chat.agentWaiting', '正在处理…');
}

/**
 * Ephemeral agent streaming bubble: shows Router thinking / reply fragments in real
 * time. It is display-only — the authoritative message replaces it once it lands.
 */
export const AgentStreamBubble = memo(function AgentStreamBubble({
  state,
  agent,
}: {
  state: AgentStreamState;
  agent?: ImParticipant;
}) {
  const { styles } = useStyles();
  const [elapsed, setElapsed] = useState(0);
  const [thinkingOpen, setThinkingOpen] = useState(true);

  useEffect(() => {
    setThinkingOpen(state.phase === 'thinking');
  }, [state.phase]);

  useEffect(() => {
    if (state.finalized) return undefined;
    const timer = window.setInterval(() => {
      setElapsed(
        Math.max(0, Math.round((Date.now() - state.startedAt) / 1000)),
      );
    }, 1000);
    return () => window.clearInterval(timer);
  }, [state.startedAt, state.finalized]);

  if (state.phase === 'done' && !state.thinking && !state.reply) {
    return null;
  }

  const agentName = agent?.displayName || tw('chat.agent', '数字分身');
  const showThinking = state.thinking.length > 0;
  const streaming = !state.finalized;

  return (
    <div className={styles.agentStreamRow}>
      <Avatar size={32} src={agent?.avatar} style={avatarStyle(agentName)}>
        {initials(agentName)}
      </Avatar>
      <div className={styles.agentStreamBubbleWrap}>
        <div className={styles.messageMeta}>
          {agentName} · {streamStatusLabel(state)}
          {streaming && elapsed > 0 ? ` · ${elapsed}s` : ''}
        </div>
        <div className={styles.agentStreamBubble}>
          {showThinking ? (
            <div className={styles.agentStreamThinking}>
              <button
                type="button"
                className={styles.agentStreamThinkingToggle}
                onClick={() => setThinkingOpen((open) => !open)}
              >
                {thinkingOpen ? <UpOutlined /> : <DownOutlined />}
                {tw('chat.thinkingProcess', '思考过程')}
              </button>
              {thinkingOpen ? (
                <div className={styles.agentStreamThinkingText}>
                  {state.thinking}
                </div>
              ) : null}
            </div>
          ) : null}
          {state.reply ? (
            <div className={styles.agentStreamReply}>
              {state.reply}
              {streaming ? (
                <span className={styles.agentStreamCursor} aria-hidden />
              ) : null}
            </div>
          ) : null}
          {state.phase === 'failed' && state.errorMessage ? (
            <div className={styles.agentStreamError}>{state.errorMessage}</div>
          ) : null}
          {!state.reply &&
          state.phase !== 'failed' &&
          state.phase !== 'done' ? (
            <div className={styles.agentStreamStatus}>
              {streamStatusLabel(state)}
              {streaming ? (
                <span className={styles.agentStreamCursor} aria-hidden />
              ) : null}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
});

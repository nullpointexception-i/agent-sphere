import { SendOutlined } from '@ant-design/icons';
import type { RefObject } from 'react';
import type { MentionSuggestController } from '@/components/mention/useMentionSuggest';
import { tw } from '../i18n';
import { MentionPicker } from './MentionPicker';

export interface MessageComposerProps {
  text: string;
  setText: (value: string) => void;
  composerRef: RefObject<HTMLTextAreaElement | null>;
  mention: MentionSuggestController;
  sending: boolean;
  agentPaused: boolean;
  onSend: () => void;
  styles: Record<string, string>;
}

/** Textarea + @ picker + send button, extracted from ConversationPane. */
export function MessageComposer({
  text,
  setText,
  composerRef,
  mention,
  sending,
  agentPaused,
  onSend,
  styles,
}: MessageComposerProps) {
  return (
    <div className={styles.composer}>
      <textarea
        ref={composerRef}
        className={styles.composerInput}
        rows={1}
        value={text}
        disabled={agentPaused}
        placeholder={tw('chat.inputPlaceholder', 'Type a message...')}
        onChange={(event) => {
          const next = event.target.value;
          setText(next);
          mention.refresh(next, event.target.selectionStart);
          const element = event.target;
          element.style.height = 'auto';
          element.style.height = `${Math.min(element.scrollHeight, 120)}px`;
        }}
        onKeyDown={(event) => {
          if (mention.handleKeyDown(event)) {
            return;
          }
          if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            onSend();
          }
        }}
        onSelect={(event) =>
          mention.refresh(
            event.currentTarget.value,
            event.currentTarget.selectionStart,
          )
        }
      />
      <MentionPicker
        open={mention.open}
        items={mention.items}
        activeIndex={mention.activeIndex}
        position={mention.position}
        onHover={mention.setActiveIndex}
        onSelect={mention.applyCandidate}
      />
      <button
        type="button"
        className={`${styles.composerSend} ${
          !text.trim() || sending || agentPaused
            ? styles.composerSendDisabled
            : ''
        }`}
        onClick={onSend}
      >
        <SendOutlined />
        <span style={{ marginLeft: 6 }}>{tw('chat.send', 'Send')}</span>
      </button>
    </div>
  );
}

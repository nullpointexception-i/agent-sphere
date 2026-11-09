import { SendOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Tag, Upload } from 'antd';
import { useMemo, useRef } from 'react';
import { MentionSuggest } from '@/components/mention/MentionSuggest';
import { buildMentionCandidates } from '@/components/mention/mentionModel';
import { useMentionSuggest } from '@/components/mention/useMentionSuggest';
import type { ConversationAgent } from '@/services/agentSphere/im';

interface Props {
  text: string;
  onTextChange: (value: string) => void;
  onSend: () => void;
  sending: boolean;
  participants: any[];
  /** Live avatar view of the conversation; groups do not store avatars as members. */
  agents?: ConversationAgent[];
  /** Excludes the current user from the @ candidate list. */
  selfUserId?: number | null;
  replyToId?: number;
  onClearReply: () => void;
  onUpload: (file: File) => void;
  labels: {
    placeholder: string;
    send: string;
    replying: string;
    attachment: string;
    candidateAgent: string;
    candidateUser: string;
    candidateSelf: string;
  };
}

/**
 * IM composer with an `@` suggestion list.
 *
 * Mentions are no longer picked from a separate multi-select: the message body
 * carries `@name` and the page rebuilds the payload from it before sending.
 */
export function Composer({
  text,
  onTextChange,
  onSend,
  sending,
  participants,
  agents,
  selfUserId,
  replyToId,
  onClearReply,
  onUpload,
  labels,
}: Props) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const candidates = useMemo(() => {
    const avatarRows = (agents || []).map((agent) => ({
      participantType: 'AGENT',
      participantId: agent.participantId,
      displayName: agent.displayName,
      ownerUserId: agent.ownerUserId,
    }));
    return buildMentionCandidates([...participants, ...avatarRows], selfUserId);
  }, [participants, agents, selfUserId]);
  const mention = useMentionSuggest({
    textareaRef,
    value: text,
    candidates,
    onChange: (nextText, caret) => {
      onTextChange(nextText);
      requestAnimationFrame(() => {
        const element = textareaRef.current;
        if (!element) return;
        element.focus();
        element.setSelectionRange(caret, caret);
      });
    },
  });

  return (
    <div
      style={{
        marginTop: 12,
        paddingTop: 12,
        paddingBottom: 16,
        borderTop: '1px solid #f0f0f0',
      }}
    >
      {replyToId ? (
        <div style={{ marginBottom: 6 }}>
          <Tag closable onClose={onClearReply}>
            {labels.replying}
          </Tag>
        </div>
      ) : null}
      <textarea
        ref={textareaRef}
        value={text}
        placeholder={labels.placeholder}
        rows={2}
        style={{
          width: '100%',
          minHeight: 54,
          maxHeight: 120,
          resize: 'vertical',
          padding: '5px 11px',
          border: '1px solid #d9d9d9',
          borderRadius: 6,
          fontSize: 14,
          lineHeight: 1.5715,
          outline: 'none',
          boxSizing: 'border-box',
        }}
        onChange={(event) => {
          const next = event.target.value;
          onTextChange(next);
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
      <MentionSuggest
        open={mention.open}
        items={mention.items}
        activeIndex={mention.activeIndex}
        position={mention.position}
        labels={{
          agent: labels.candidateAgent,
          user: labels.candidateUser,
          self: labels.candidateSelf,
        }}
        onHover={mention.setActiveIndex}
        onSelect={mention.applyCandidate}
      />
      <div style={{ textAlign: 'right', marginTop: 8 }}>
        <Upload
          showUploadList={false}
          beforeUpload={(file) => {
            onUpload(file);
            return Upload.LIST_IGNORE;
          }}
          disabled={sending}
        >
          <Button icon={<UploadOutlined />} disabled={sending}>
            {labels.attachment}
          </Button>
        </Upload>
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={sending}
          onClick={onSend}
          style={{ marginLeft: 8 }}
        >
          {labels.send}
        </Button>
      </div>
    </div>
  );
}

import XMarkdown from '@ant-design/x-markdown';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { memo } from 'react';
import { useChatStyles } from './chat/style';

/**
 * Shared markdown renderer for DM agent messages. The streaming reply bubble and
 * the authoritative message use the same styles so the swap does not jump.
 */
export const MessageMarkdown = memo(function MessageMarkdown({
  text,
  streaming,
}: {
  text: string;
  streaming?: boolean;
}) {
  const { styles } = useChatStyles();
  const className = streaming
    ? `${styles.messageMarkdown} ${styles.messageMarkdownStreaming}`
    : styles.messageMarkdown;
  return (
    <div className={className}>
      <XMarkdown content={text || ''} />
    </div>
  );
});

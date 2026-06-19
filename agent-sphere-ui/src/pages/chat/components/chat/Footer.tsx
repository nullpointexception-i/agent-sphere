import { Sender } from '@ant-design/x';
import type { SenderRef } from '@ant-design/x/es/sender/interface';
import { Tooltip } from 'antd';
import { FullscreenOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { useEffect, useRef } from 'react';

interface FooterProps {
  inputValue: string;
  onInputValueChange: (v: string) => void;
  sending: boolean;
  onSendMessage: () => void;
  onCancel: () => void;
  onExpandOpen: () => void;
  sessionKey?: string;
}

export default function Footer({
  inputValue, onInputValueChange, sending, onSendMessage, onCancel, onExpandOpen, sessionKey,
}: FooterProps) {
  const intl = useIntl();
  const senderRef = useRef<SenderRef>(null);

  useEffect(() => {
    senderRef.current?.focus();
  }, [sessionKey]);

  return (
    <div style={{ display: 'flex', gap: 8, maxWidth: 940, width: '100%', alignItems: 'center' }}>
      <Sender
        ref={senderRef}
        value={inputValue}
        onChange={onInputValueChange}
        loading={sending}
        submitType="enter"
        onSubmit={onSendMessage}
        onCancel={onCancel}
        placeholder={intl.formatMessage({ id: 'pages.chat.typeMessageHint', defaultMessage: 'Type a message... (Shift+Enter for new line)' })}
        maxLength={5000}
        style={{ flex: 1 }}
        autoSize={false}
        suffix={(oriNode) => (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, transform: 'translateY(-3px)' }}>
            <Tooltip title={intl.formatMessage({ id: 'pages.chat.expandInput', defaultMessage: 'Expand Input' })}>
              <FullscreenOutlined
                style={{ fontSize: 16, cursor: 'pointer', color: '#8c8c8c' }}
                onClick={onExpandOpen}
              />
            </Tooltip>
            {oriNode}
          </span>
        )}
        styles={{ root: { height: 60, borderRadius: 24, display: 'flex', flexDirection: 'column' }, content: { flex: 1, alignItems: 'center', paddingBlock: 8 }, input: { display: 'flex', alignItems: 'center', paddingTop: 6, overflowY: 'auto', resize: 'none' }, suffix: { alignItems: 'center' } }}
      />
    </div>
  );
}

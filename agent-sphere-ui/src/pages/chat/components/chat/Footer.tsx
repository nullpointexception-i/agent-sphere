import { Sender } from '@ant-design/x';
import type { SenderRef } from '@ant-design/x/es/sender/interface';
import { Tooltip } from 'antd';
import { FullscreenOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { useEffect, useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

interface FooterProps {
  inputValue: string;
  onInputValueChange: (v: string) => void;
  sending: boolean;
  onSendMessage: () => void;
  onCancel: () => void;
  onExpandOpen: () => void;
  sessionKey?: string;
  sessionId?: number;
}

export default function Footer({
  inputValue, onInputValueChange, sending, onSendMessage, onCancel, onExpandOpen,
  sessionKey, sessionId,
}: FooterProps) {
  const intl = useIntl();
  const senderRef = useRef<SenderRef>(null);
  const [historyRunId, setHistoryRunId] = useState<number | null>(null);
  const [savedInput, setSavedInput] = useState('');
  const [loadingHistory, setLoadingHistory] = useState(false);
  const inputRef = useRef(inputValue);
  const historyRunIdRef = useRef(historyRunId);
  const savedInputRef = useRef(savedInput);

  inputRef.current = inputValue;
  historyRunIdRef.current = historyRunId;
  savedInputRef.current = savedInput;

  useEffect(() => {
    senderRef.current?.focus();
  }, [sessionKey]);

  useEffect(() => {
    const el = senderRef.current?.nativeElement;
    if (!el) return;
    const textarea = el.querySelector('textarea');
    if (!textarea) return;

    const onKeyDown = async (e: Event) => {
      const ke = e as KeyboardEvent;
      if (!sessionId) return;

      if (ke.key === 'ArrowUp') {
        if (sending || loadingHistory) return;
        const cursorPos = textarea.selectionStart ?? 0;
        if (inputRef.current.length > 0 && cursorPos !== 0) return;

        ke.preventDefault();
        setLoadingHistory(true);
        try {
          const res = await agentApi.sessions.messageHistory(
            sessionId, 'prev', historyRunIdRef.current ?? undefined,
          );
          if (res.userMessage != null) {
            if (historyRunIdRef.current == null) setSavedInput(inputRef.current);
            setHistoryRunId(res.runId);
            onInputValueChange(res.userMessage);
          }
        } finally {
          setLoadingHistory(false);
        }
      } else if (ke.key === 'ArrowDown') {
        if (historyRunIdRef.current == null || sending || loadingHistory) return;
        ke.preventDefault();
        setLoadingHistory(true);
        try {
          const res = await agentApi.sessions.messageHistory(
            sessionId, 'next', historyRunIdRef.current,
          );
          if (res.userMessage != null) {
            setHistoryRunId(res.runId);
            onInputValueChange(res.userMessage);
          } else {
            setHistoryRunId(null);
            onInputValueChange(savedInputRef.current);
            setSavedInput('');
          }
        } finally {
          setLoadingHistory(false);
        }
      }
    };

    textarea.addEventListener('keydown', onKeyDown);
    return () => textarea.removeEventListener('keydown', onKeyDown);
  }, [sessionId, sending, loadingHistory, onInputValueChange]);

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

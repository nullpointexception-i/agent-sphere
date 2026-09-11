import {
  AudioOutlined,
  CloseOutlined,
  FullscreenOutlined,
  PictureOutlined,
} from '@ant-design/icons';
import { Sender } from '@ant-design/x';
import type { SenderRef } from '@ant-design/x/es/sender/interface';
import { useIntl } from '@umijs/max';
import { message, Tooltip } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { useVoiceInput } from './useVoiceInput';

interface FooterProps {
  inputValue: string;
  onInputValueChange: (v: string) => void;
  sending: boolean;
  hasPendingClarifications?: boolean;
  onSendMessage: (attachmentKeys?: string[]) => void;
  onCancel: () => void;
  onExpandOpen: () => void;
  sessionKey?: string;
  sessionId?: number;
}

interface PendingAttachment {
  fileKey: string;
  contentType: string;
  previewUrl: string;
}

export default function Footer({
  inputValue,
  onInputValueChange,
  sending,
  hasPendingClarifications,
  onSendMessage,
  onCancel,
  onExpandOpen,
  sessionKey,
  sessionId,
}: FooterProps) {
  const intl = useIntl();
  const senderRef = useRef<SenderRef>(null);
  const [historyRunId, setHistoryRunId] = useState<number | null>(null);
  const [savedInput, setSavedInput] = useState('');
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const [uploadingAttachment, setUploadingAttachment] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef(inputValue);
  const historyRunIdRef = useRef(historyRunId);
  const savedInputRef = useRef(savedInput);
  inputRef.current = inputValue;
  historyRunIdRef.current = historyRunId;
  savedInputRef.current = savedInput;

  const { isRecording, toggleRecording } = useVoiceInput({
    inputRef,
    onInputValueChange,
    sending,
    messageApi: message,
    intl,
  });

  const toggleRecordingRef = useRef(toggleRecording);
  toggleRecordingRef.current = toggleRecording;

  const uploadImageFile = async (file: File) => {
    if (!/^image\/(jpeg|png|webp|gif)$/i.test(file.type)) {
      message.warning(
        intl.formatMessage({
          id: 'pages.chat.attachmentTypeHint',
          defaultMessage: '仅支持 jpeg/png/webp/gif 图片',
        }),
      );
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      message.warning(
        intl.formatMessage({
          id: 'pages.chat.attachmentSizeHint',
          defaultMessage: '图片不能超过 5MB',
        }),
      );
      return;
    }
    try {
      setUploadingAttachment(true);
      const res = await agentApi.files.upload(file);
      const previewUrl = URL.createObjectURL(file);
      setAttachments([
        {
          fileKey: res.fileKey,
          contentType: res.contentType,
          previewUrl,
        },
      ]);
    } catch (err) {
      message.error(
        intl.formatMessage({
          id: 'pages.chat.attachmentUploadFailed',
          defaultMessage: '附件上传失败',
        }),
      );
      console.warn('[attachment] upload failed:', err);
    } finally {
      setUploadingAttachment(false);
    }
  };

  const attachmentsRef = useRef(attachments);
  const uploadingAttachmentRef = useRef(uploadingAttachment);
  const sendingRef = useRef(sending);
  const uploadImageFileRef = useRef(uploadImageFile);
  const intlRef = useRef(intl);
  attachmentsRef.current = attachments;
  uploadingAttachmentRef.current = uploadingAttachment;
  sendingRef.current = sending;
  uploadImageFileRef.current = uploadImageFile;
  intlRef.current = intl;

  useEffect(() => {
    senderRef.current?.focus();
  }, [sessionKey]);

  useEffect(() => {
    const el = senderRef.current?.nativeElement;
    if (!el) return;
    const textarea = el.querySelector('textarea');
    if (!textarea) return;

    const onKeyDown = (e: Event) => {
      const ke = e as KeyboardEvent;
      if (ke.key === ' ' && ke.shiftKey && !ke.repeat) {
        ke.preventDefault();
        toggleRecordingRef.current();
      }
    };

    textarea.addEventListener('keydown', onKeyDown);
    return () => textarea.removeEventListener('keydown', onKeyDown);
  }, []);

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
            sessionId,
            'prev',
            historyRunIdRef.current ?? undefined,
          );
          if (res.userMessage != null) {
            if (historyRunIdRef.current == null)
              setSavedInput(inputRef.current);
            setHistoryRunId(res.runId);
            onInputValueChange(res.userMessage);
          }
        } finally {
          setLoadingHistory(false);
        }
      } else if (ke.key === 'ArrowDown') {
        if (historyRunIdRef.current == null || sending || loadingHistory)
          return;
        ke.preventDefault();
        setLoadingHistory(true);
        try {
          const res = await agentApi.sessions.messageHistory(
            sessionId,
            'next',
            historyRunIdRef.current,
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

  useEffect(() => {
    const el = senderRef.current?.nativeElement;
    if (!el) return;
    const textarea = el.querySelector('textarea');
    if (!textarea) return;

    const onPaste = (e: Event) => {
      const pe = e as ClipboardEvent;
      const files: File[] = [];
      const dataTransfer = pe.clipboardData;
      if (dataTransfer) {
        if (dataTransfer.files && dataTransfer.files.length > 0) {
          files.push(...Array.from(dataTransfer.files));
        } else if (dataTransfer.items) {
          for (const item of Array.from(dataTransfer.items)) {
            if (item.kind === 'file') {
              const f = item.getAsFile();
              if (f) files.push(f);
            }
          }
        }
      }
      const image = files.find((f) => f.type.startsWith('image/'));
      if (!image) return;
      // 消费图片粘贴（与图片按钮共用单附件规则：已有/上传中/发送中则提示并忽略）
      pe.preventDefault();
      if (
        attachmentsRef.current.length > 0 ||
        uploadingAttachmentRef.current ||
        sendingRef.current
      ) {
        message.warning(
          intlRef.current.formatMessage({
            id: 'pages.chat.attachmentBusyHint',
            defaultMessage: '已有待发送附件，请先发送或删除后再粘贴',
          }),
        );
        return;
      }
      void uploadImageFileRef.current(image);
    };

    textarea.addEventListener('paste', onPaste);
    return () => textarea.removeEventListener('paste', onPaste);
  }, []);

  const handlePickImage = () => {
    if (attachments.length > 0 || uploadingAttachment || sending) return;
    fileInputRef.current?.click();
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    await uploadImageFileRef.current(file);
  };

  const handleSubmitAttachment = () => {
    if (attachments.length === 0) {
      onSendMessage();
      return;
    }
    const keys = attachments.map((a) => a.fileKey);
    setAttachments([]);
    onSendMessage(keys);
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        maxWidth: 940,
        width: '100%',
      }}
    >
      <style>{`
        @keyframes voice-pulse {
          0% { box-shadow: 0 0 0 0 rgba(255,77,77,0.4); }
          70% { box-shadow: 0 0 0 8px rgba(255,77,77,0); }
          100% { box-shadow: 0 0 0 0 rgba(255,77,77,0); }
        }
        .voice-pulse {
          animation: voice-pulse 1.5s infinite;
          border-radius: 50%;
        }
      `}</style>
      {attachments.length > 0 && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            paddingLeft: 8,
          }}
        >
          {attachments.map((a, idx) => (
            <div
              key={a.fileKey}
              style={{
                position: 'relative',
                width: 64,
                height: 64,
                borderRadius: 8,
                overflow: 'hidden',
                border: '1px solid #e5e5e5',
              }}
            >
              <img
                src={a.previewUrl}
                alt="attachment"
                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              />
              <CloseOutlined
                style={{
                  position: 'absolute',
                  top: 2,
                  right: 2,
                  fontSize: 12,
                  color: '#fff',
                  background: 'rgba(0,0,0,0.5)',
                  borderRadius: '50%',
                  padding: 2,
                  cursor: 'pointer',
                }}
                onClick={() =>
                  setAttachments((prev) => prev.filter((_, i) => i !== idx))
                }
              />
            </div>
          ))}
        </div>
      )}
      <div
        style={{
          display: 'flex',
          gap: 8,
          maxWidth: 940,
          width: '100%',
          alignItems: 'center',
        }}
      >
        <Sender
          ref={senderRef}
          value={inputValue}
          onChange={onInputValueChange}
          loading={sending || hasPendingClarifications}
          submitType="enter"
          onSubmit={handleSubmitAttachment}
          onCancel={onCancel}
          placeholder={intl.formatMessage({
            id: 'pages.chat.typeMessageHint',
            defaultMessage: 'Type a message... (Shift+Enter for new line)',
          })}
          maxLength={5000}
          style={{ flex: 1 }}
          autoSize={false}
          suffix={(oriNode) => (
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                transform: 'translateY(-3px)',
              }}
            >
              <Tooltip
                title={intl.formatMessage({
                  id: 'pages.chat.attachImage',
                  defaultMessage: 'Attach image',
                })}
              >
                <PictureOutlined
                  style={{
                    fontSize: 16,
                    cursor:
                      attachments.length > 0 || uploadingAttachment || sending
                        ? 'not-allowed'
                        : 'pointer',
                    color: uploadingAttachment ? '#bfbfbf' : '#8c8c8c',
                  }}
                  onClick={handlePickImage}
                />
              </Tooltip>
              <Tooltip
                title={intl.formatMessage({
                  id: isRecording
                    ? 'pages.chat.voiceInputStop'
                    : 'pages.chat.voiceInput',
                })}
              >
                <AudioOutlined
                  className={isRecording ? 'voice-pulse' : ''}
                  style={{
                    fontSize: 16,
                    cursor: 'pointer',
                    color: isRecording ? '#ff4d4f' : '#8c8c8c',
                  }}
                  onClick={toggleRecording}
                />
              </Tooltip>
              <Tooltip
                title={intl.formatMessage({
                  id: 'pages.chat.expandInput',
                  defaultMessage: 'Expand Input',
                })}
              >
                <FullscreenOutlined
                  style={{ fontSize: 16, cursor: 'pointer', color: '#8c8c8c' }}
                  onClick={onExpandOpen}
                />
              </Tooltip>
              {oriNode}
            </span>
          )}
          styles={{
            root: {
              height: 60,
              borderRadius: 24,
              display: 'flex',
              flexDirection: 'column',
            },
            content: { flex: 1, alignItems: 'center', paddingBlock: 8 },
            input: {
              display: 'flex',
              alignItems: 'center',
              paddingTop: 6,
              overflowY: 'auto',
              resize: 'none',
              outline: 'none',
            },
            suffix: { alignItems: 'center' },
          }}
        />
      </div>
      <input
        ref={fileInputRef}
        type="file"
        accept="image/png,image/jpeg,image/webp,image/gif"
        style={{ display: 'none' }}
        onChange={handleFileChange}
      />
    </div>
  );
}

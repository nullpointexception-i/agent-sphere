import { AudioOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import { Sender } from '@ant-design/x';
import type { SenderRef } from '@ant-design/x/es/sender/interface';
import { useIntl } from '@umijs/max';
import { App, Tag, Tooltip } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useStyles } from '../../style';
import { useVoiceInput } from '../chat/useVoiceInput';

interface LandingProps {
  instances: any[];
  chosenInstance: string;
  currentInstanceObj: any | null;
  onInstanceChange: (inst: any) => void;
  inputValue: string;
  onInputValueChange: (v: string) => void;
  sending: boolean;
  onStartSession: () => void;
  onOpenInstanceDrawer: () => void;
  onCreateInstance: () => void;
  onConfigureModelRoute: (inst: any) => void;
}

export default function Landing({
  instances,
  chosenInstance,
  currentInstanceObj,
  onInstanceChange,
  inputValue,
  onInputValueChange,
  sending,
  onStartSession,
  onOpenInstanceDrawer,
  onCreateInstance,
  onConfigureModelRoute,
}: LandingProps) {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const { styles } = useStyles();
  const senderRef = useRef<SenderRef>(null);
  const inputRef = useRef(inputValue);
  inputRef.current = inputValue;

  const { isRecording, toggleRecording } = useVoiceInput({
    inputRef,
    onInputValueChange,
    sending,
    messageApi: message,
    intl,
  });

  const [dataLoaded, setDataLoaded] = useState(false);

  useEffect(() => {
    senderRef.current?.focus();
  }, []);

  useEffect(() => {
    if (instances.length > 0 || chosenInstance) {
      setDataLoaded(true);
    }
  }, [instances, chosenInstance]);

  useEffect(() => {
    const t = setTimeout(() => setDataLoaded(true), 3000);
    return () => clearTimeout(t);
  }, []);

  const toggleRecordingRef = useRef(toggleRecording);
  toggleRecordingRef.current = toggleRecording;

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

  const currentInstance = useMemo(() => {
    if (!chosenInstance) return null;
    return (
      currentInstanceObj ||
      instances.find((i: any) => String(i.id) === chosenInstance) ||
      null
    );
  }, [instances, chosenInstance, currentInstanceObj]);

  const noData = dataLoaded && instances.length === 0;

  return (
    <div className={styles.landing}>
      <div className={styles.landingTitle}>
        {intl.formatMessage({ id: 'pages.landing.title' })}
      </div>

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
      <div className={styles.landingRow}>
        <Sender
          ref={senderRef}
          header={
            currentInstance ? (
              <div style={{ padding: '6px 12px 0' }}>
                <Tag
                  color="blue"
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    lineHeight: '22px',
                  }}
                >
                  {currentInstance.name}
                </Tag>
              </div>
            ) : undefined
          }
          value={inputValue}
          onChange={onInputValueChange}
          loading={sending}
          disabled={noData}
          onSubmit={onStartSession}
          placeholder={intl.formatMessage({
            id: 'pages.chat.typeMessageHint',
            defaultMessage: 'Type a message... (Shift+Enter for new line)',
          })}
          maxLength={5000}
          style={{ flex: 1 }}
          autoSize={false}
          styles={{
            root: {
              height: noData ? 60 : 140,
              borderRadius: 24,
              display: 'flex',
              flexDirection: 'column',
            },
            content: { paddingBlock: 0, flex: 1, alignItems: 'center' },
            input: {
              display: 'flex',
              alignItems: 'center',
              overflowY: 'auto',
              resize: 'none',
            },
          }}
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
                  id: isRecording ? 'pages.chat.voiceInputStop' : 'pages.chat.voiceInput',
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
              {oriNode}
            </span>
          )}
        />
      </div>

      {instances.length > 0 && (
        <>
          <div className={styles.instanceCardsRow}>
            {instances.slice(0, 3).map((inst: any) => {
              const isActive = String(inst.id) === chosenInstance;
              return (
                <div
                  key={inst.id}
                  className={`${styles.instanceCard} ${isActive ? 'instanceCard-active' : ''}`}
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    ...(isActive
                      ? { borderColor: '#1677ff', background: '#e6f4ff' }
                      : undefined),
                    ...(!inst.modelRouteId
                      ? { opacity: 0.45, cursor: 'not-allowed' }
                      : { cursor: 'pointer' }),
                  }}
                  onClick={() => {
                    if (!inst.modelRouteId) {
                      modal.confirm({
                        title: intl.formatMessage({
                          id: 'pages.landing.noModelRouteTitle',
                          defaultMessage: '未配置默认模型',
                        }),
                        content: intl.formatMessage({
                          id: 'pages.landing.noModelRouteConfirm',
                          defaultMessage: '是否去为该实例配置模型？',
                        }),
                        okText: intl.formatMessage({
                          id: 'pages.landing.goConfigure',
                          defaultMessage: '去配置',
                        }),
                        cancelText: intl.formatMessage({
                          id: 'pages.cancel',
                          defaultMessage: '取消',
                        }),
                        onOk: () => {
                          onConfigureModelRoute(inst);
                        },
                      });
                      return;
                    }
                    onInstanceChange(inst);
                  }}
                >
                  <div className={styles.instanceCardName}>{inst.name}</div>
                  {inst.description && (
                    <div className={styles.instanceCardDesc}>
                      {inst.description}
                    </div>
                  )}
                  {!inst.modelRouteId && (
                    <div
                      style={{
                        fontSize: 11,
                        color: '#ff4d4f',
                        marginTop: 'auto',
                        paddingTop: 6,
                        display: 'flex',
                        alignItems: 'center',
                        gap: 2,
                      }}
                    >
                      <ExclamationCircleOutlined />
                      {intl.formatMessage({
                        id: 'pages.landing.needModelRoute',
                        defaultMessage: '需要设置默认模型',
                      })}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
          <span className={styles.moreLink} onClick={onOpenInstanceDrawer}>
            {intl.formatMessage({ id: 'pages.landing.more' })} →
          </span>
        </>
      )}

      {noData && (
        <div className={styles.emptyInstanceState}>
          <div className={styles.emptyInstanceIcon}>📋</div>
          <div className={styles.emptyInstanceTitle}>
            {intl.formatMessage({ id: 'pages.landing.noInstance' })}
          </div>
          <div className={styles.emptyInstanceDesc}>
            {intl.formatMessage({ id: 'pages.landing.noInstanceDesc' })}
          </div>
          <span className={styles.moreLink} onClick={onCreateInstance}>
            {intl.formatMessage({ id: 'pages.landing.createInstance' })} →
          </span>
        </div>
      )}
    </div>
  );
}

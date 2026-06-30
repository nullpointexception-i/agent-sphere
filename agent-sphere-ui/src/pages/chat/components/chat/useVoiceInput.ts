import { useCallback, useEffect, useRef, useState } from 'react';

interface UseVoiceInputOptions {
  inputRef: React.MutableRefObject<string>;
  onInputValueChange: (value: string) => void;
  sending: boolean;
  messageApi: {
    warning: (msg: string) => void;
    error: (msg: string) => void;
  };
  intl: {
    formatMessage: (desc: { id: string; defaultMessage?: string }) => string;
  };
}

export function useVoiceInput({
  inputRef,
  onInputValueChange,
  sending,
  messageApi,
  intl,
}: UseVoiceInputOptions) {
  const [isRecording, setIsRecording] = useState(false);
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const lastTranscriptRef = useRef('');
  const silenceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const SILENCE_TIMEOUT = 10000;

  const toggleRecording = useCallback(() => {
    if (sending) return;

    if (isRecording) {
      if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
      recognitionRef.current?.stop();
      setIsRecording(false);
      return;
    }

    if (!navigator.onLine) {
      messageApi.warning(
        intl.formatMessage({ id: 'pages.chat.voiceInputOffline' }),
      );
      return;
    }

    const SpeechRecognitionAPI =
      window.SpeechRecognition ?? window.webkitSpeechRecognition;
    if (!SpeechRecognitionAPI) {
      messageApi.warning(
        intl.formatMessage({ id: 'pages.chat.voiceInputNotSupported' }),
      );
      return;
    }

    try {
      const recognition = new SpeechRecognitionAPI();
      recognition.lang = 'zh-CN';
      recognition.interimResults = true;
      recognition.continuous = true;

      recognition.onresult = (event: SpeechRecognitionEvent) => {
        let full = '';
        for (let i = 0; i < event.results.length; i++) {
          full += event.results[i][0].transcript;
        }
        const prev = lastTranscriptRef.current;
        lastTranscriptRef.current = full;
        if (full.startsWith(prev) && full.length > prev.length) {
          const delta = full.slice(prev.length);
          onInputValueChange(inputRef.current + delta);
        }
        if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
        silenceTimerRef.current = setTimeout(
          () => recognitionRef.current?.stop(),
          SILENCE_TIMEOUT,
        );
      };

      recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
        if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
        setIsRecording(false);
        if (event.error === 'network') {
          messageApi.warning(
            intl.formatMessage({ id: 'pages.chat.voiceInputNetworkError' }),
          );
        } else if (event.error === 'not-allowed') {
          messageApi.error(
            intl.formatMessage({ id: 'pages.chat.voiceInputNotAllowed' }),
          );
        } else if (event.error !== 'no-speech') {
          messageApi.error(
            intl.formatMessage({ id: 'pages.chat.voiceInputError' }),
          );
        }
      };

      recognition.onend = () => {
        if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
        setIsRecording(false);
      };

      recognitionRef.current = recognition;
      lastTranscriptRef.current = '';
      silenceTimerRef.current = setTimeout(
        () => recognitionRef.current?.stop(),
        SILENCE_TIMEOUT,
      );
      recognition.start();
      setIsRecording(true);
    } catch {
      messageApi.error(
        intl.formatMessage({ id: 'pages.chat.voiceInputStartFailed' }),
      );
    }
  }, [isRecording, onInputValueChange, sending, messageApi, intl]);

  useEffect(() => {
    return () => {
      if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
      recognitionRef.current?.abort();
    };
  }, []);

  return { isRecording, toggleRecording };
}

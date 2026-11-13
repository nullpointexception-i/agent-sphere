import {
  type KeyboardEvent as ReactKeyboardEvent,
  type RefObject,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { caretCoordinates } from './caretCoordinates';
import {
  arrangeMentionRows,
  detectMentionQuery,
  insertMention,
  type MentionCandidate,
  type MentionRange,
} from './mentionModel';

export interface MentionPosition {
  left: number;
  top: number;
  placement: 'above' | 'below';
}

interface Options {
  textareaRef: RefObject<HTMLTextAreaElement | null>;
  value: string;
  candidates: MentionCandidate[];
  /** Receives the new text plus the caret index to restore. */
  onChange: (text: string, caret: number) => void;
}

const POPUP_WIDTH = 280;
const POPUP_SAFE_MARGIN = 8;
/** Below this viewport offset the list opens downwards instead of upwards. */
const FLIP_THRESHOLD = 300;

/**
 * State machine of the `@` suggestion popup.
 *
 * Owns the active range, the filtered list, keyboard navigation and the
 * caret-anchored position; rendering stays in {@link MentionSuggest}.
 */
export function useMentionSuggest({
  textareaRef,
  value,
  candidates,
  onChange,
}: Options) {
  const [range, setRange] = useState<MentionRange | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [position, setPosition] = useState<MentionPosition | null>(null);

  const items = useMemo(
    () => (range ? arrangeMentionRows(candidates, range.query) : []),
    [candidates, range],
  );
  const open = Boolean(range) && items.length > 0;

  const close = useCallback(() => setRange(null), []);

  const refresh = useCallback((nextValue: string, caret?: number | null) => {
    const next = detectMentionQuery(
      nextValue,
      caret == null ? nextValue.length : caret,
    );
    setRange(next);
    if (next) {
      setActiveIndex(0);
    }
  }, []);

  const updatePosition = useCallback(() => {
    const textarea = textareaRef.current;
    if (!textarea || !range) {
      setPosition(null);
      return;
    }
    const point = caretCoordinates(textarea, range.start);
    if (!point) {
      setPosition(null);
      return;
    }
    const rect = textarea.getBoundingClientRect();
    const left = Math.min(
      Math.max(POPUP_SAFE_MARGIN, rect.left + point.left),
      Math.max(
        POPUP_SAFE_MARGIN,
        window.innerWidth - POPUP_WIDTH - POPUP_SAFE_MARGIN,
      ),
    );
    const caretTop = rect.top + point.top;
    const placement = caretTop > FLIP_THRESHOLD ? 'above' : 'below';
    setPosition({
      left,
      top:
        placement === 'above' ? caretTop - 6 : caretTop + point.lineHeight + 6,
      placement,
    });
  }, [range, textareaRef]);

  // The caret moves with typing, so re-measure whenever the text changes.
  useEffect(() => {
    if (!range) {
      return;
    }
    updatePosition();
  }, [range, value, updatePosition]);

  useEffect(() => {
    if (!range) {
      return;
    }
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);
    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [range, updatePosition]);

  // Dismiss when the user clicks anywhere outside the textarea and the list.
  useEffect(() => {
    if (!range) {
      return;
    }
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as HTMLElement | null;
      if (!target) {
        return;
      }
      if (target.closest('[data-mention-popup]')) {
        return;
      }
      if (target === textareaRef.current) {
        return;
      }
      setRange(null);
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [range, textareaRef]);

  const applyCandidate = useCallback(
    (candidate: MentionCandidate) => {
      if (!range || !candidate) {
        return;
      }
      const next = insertMention(value, range, candidate);
      setRange(null);
      onChange(next.text, next.caret);
    },
    [onChange, range, value],
  );

  const applyIndex = useCallback(
    (index: number) => {
      const row = items[index];
      if (row) {
        applyCandidate(row.candidate);
      }
    },
    [applyCandidate, items],
  );

  /**
   * Returns true when the key was consumed (navigation/selection). Callers must
   * skip their own Enter-to-send handling in that case.
   */
  const handleKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLTextAreaElement>): boolean => {
      if (!open || event.nativeEvent.isComposing) {
        return false;
      }
      switch (event.key) {
        case 'ArrowDown':
          event.preventDefault();
          setActiveIndex((current) => (current + 1) % items.length);
          return true;
        case 'ArrowUp':
          event.preventDefault();
          setActiveIndex(
            (current) => (current - 1 + items.length) % items.length,
          );
          return true;
        case 'Enter':
        case 'Tab':
          event.preventDefault();
          applyIndex(activeIndex);
          return true;
        case 'Escape':
          event.preventDefault();
          setRange(null);
          return true;
        default:
          return false;
      }
    },
    [activeIndex, applyIndex, items.length, open],
  );

  return {
    open,
    items,
    activeIndex,
    position,
    range,
    refresh,
    close,
    setActiveIndex,
    applyCandidate,
    applyIndex,
    handleKeyDown,
  };
}

export type MentionSuggestController = ReturnType<typeof useMentionSuggest>;

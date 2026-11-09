import { useEffect, useRef, useState } from 'react';
import { tw } from './i18n';
import { MessageMarkdown } from './MessageMarkdown';

const DEFAULT_MAX_HEIGHT = 260;

/**
 * Markdown body that collapses to a fixed height with an expand toggle.
 *
 * Worker results can be long reports; rendering them as raw text in a card is
 * both unreadable and unbounded, so this keeps the card scannable while the
 * full content stays one click away.
 */
export function CollapsibleMarkdown({
  text,
  maxHeight = DEFAULT_MAX_HEIGHT,
}: {
  text: string;
  maxHeight?: number;
}) {
  const [expanded, setExpanded] = useState(false);
  const [overflowing, setOverflowing] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = bodyRef.current;
    if (!element) {
      setOverflowing(false);
      return;
    }
    // scrollHeight is the full content height even while the body is clipped.
    setOverflowing(element.scrollHeight > maxHeight + 8);
  }, [text, maxHeight, expanded]);

  return (
    <div>
      <div
        ref={bodyRef}
        style={{
          position: 'relative',
          maxHeight: expanded ? undefined : maxHeight,
          overflow: expanded ? 'visible' : 'hidden',
        }}
      >
        <MessageMarkdown text={text} />
        {!expanded && overflowing ? (
          <div
            aria-hidden="true"
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              height: 72,
              background: 'linear-gradient(rgba(255,255,255,0), #ffffff)',
              pointerEvents: 'none',
            }}
          />
        ) : null}
      </div>
      {overflowing ? (
        <button
          type="button"
          onClick={() => setExpanded((previous) => !previous)}
          style={{
            marginTop: 6,
            padding: 0,
            border: 'none',
            background: 'transparent',
            color: '#7c6cff',
            fontSize: 12,
            cursor: 'pointer',
          }}
        >
          {expanded
            ? tw('chat.collapse', 'Collapse')
            : tw('chat.expand', 'Expand')}
        </button>
      ) : null}
    </div>
  );
}

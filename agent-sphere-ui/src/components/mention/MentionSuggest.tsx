import { useEffect, useRef } from 'react';
import type { MentionRow } from './mentionModel';
import type { MentionPosition } from './useMentionSuggest';

export interface MentionSuggestLabels {
  agent: string;
  user: string;
  self: string;
}

interface Props {
  open: boolean;
  items: MentionRow[];
  activeIndex: number;
  position: MentionPosition | null;
  labels: MentionSuggestLabels;
  onHover: (index: number) => void;
  onSelect: (candidate: MentionRow['candidate']) => void;
}

function initials(name: string): string {
  return (name || '?').trim().slice(0, 1).toUpperCase();
}

/**
 * Caret-anchored `@` suggestion list.
 *
 * Members are top-level rows; their avatars are nested underneath, so it is
 * always clear who a digital avatar belongs to. Positioned as a fixed layer so
 * it is not clipped by the scrollable message column.
 */
export function MentionSuggest({
  open,
  items,
  activeIndex,
  position,
  labels,
  onHover,
  onSelect,
}: Props) {
  const listRef = useRef<HTMLDivElement>(null);

  // Keep the highlighted row visible while arrowing through a long list.
  useEffect(() => {
    if (!open) {
      return;
    }
    const active = listRef.current?.querySelector<HTMLElement>(
      `[data-mention-index="${activeIndex}"]`,
    );
    active?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex, open]);

  if (!open || !position) {
    return null;
  }

  return (
    <div
      data-mention-popup="true"
      ref={listRef}
      role="listbox"
      style={{
        position: 'fixed',
        left: position.left,
        top: position.top,
        width: 300,
        maxHeight: 260,
        overflowY: 'auto',
        padding: 4,
        background: '#fff',
        border: '1px solid #ece8f6',
        borderRadius: 10,
        boxShadow: '0 12px 32px rgba(44, 34, 94, 0.14)',
        zIndex: 1200,
        transform:
          position.placement === 'above' ? 'translateY(-100%)' : 'none',
      }}
    >
      {items.map((row, index) => {
        const candidate = row.candidate;
        const active = index === activeIndex;
        const nested = row.depth > 0;
        return (
          <div
            key={row.key}
            data-mention-index={index}
            role="option"
            aria-selected={active}
            tabIndex={-1}
            // preventDefault keeps focus in the textarea while clicking.
            onMouseDown={(event) => event.preventDefault()}
            onMouseEnter={() => onHover(index)}
            onClick={() => onSelect(candidate)}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '6px 8px',
              paddingLeft: 8 + row.depth * 22,
              borderRadius: 8,
              cursor: 'pointer',
              background: active ? '#f1efff' : 'transparent',
            }}
          >
            {nested ? (
              <span
                aria-hidden="true"
                style={{ color: '#cfc9e6', fontSize: 12, marginRight: -2 }}
              >
                └
              </span>
            ) : null}
            {candidate.avatar ? (
              <img
                src={candidate.avatar}
                alt=""
                style={{
                  width: nested ? 24 : 26,
                  height: nested ? 24 : 26,
                  borderRadius: '50%',
                  objectFit: 'cover',
                  flex: '0 0 auto',
                }}
              />
            ) : (
              <span
                style={{
                  width: nested ? 24 : 26,
                  height: nested ? 24 : 26,
                  borderRadius: '50%',
                  flex: '0 0 auto',
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 12,
                  color: '#fff',
                  background:
                    candidate.type === 'AGENT'
                      ? 'linear-gradient(135deg, #8b7bff, #5b8cff)'
                      : 'linear-gradient(135deg, #9aa4b2, #6b7280)',
                }}
              >
                {initials(candidate.name)}
              </span>
            )}
            <span
              style={{
                flex: 1,
                minWidth: 0,
                fontSize: 13,
                color: '#27272a',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {candidate.name}
            </span>
            <span
              style={{
                flex: '0 0 auto',
                fontSize: 11,
                color: '#8b8b93',
              }}
            >
              {candidate.type === 'AGENT'
                ? labels.agent
                : candidate.isSelf
                  ? labels.self
                  : labels.user}
            </span>
          </div>
        );
      })}
    </div>
  );
}

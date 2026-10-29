import { useEffect, useMemo, useState } from 'react';
import type { ClarificationBatch } from '../types';

interface Props {
  batch: ClarificationBatch | null;
  submitting?: boolean;
  bottomOffset?: number;
  onResolve: (
    answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
  ) => void;
  onCancel?: () => void;
}

interface Answer {
  selected: string[];
  text: string;
}

function parseOptions(raw?: string): Array<{ label: string; value: string }> {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .map((o: any) =>
        typeof o === 'string'
          ? { label: o, value: o }
          : { label: String(o?.label ?? o?.value ?? ''), value: String(o?.value ?? o?.label ?? '') },
      )
      .filter((o) => o.value);
  } catch {
    return [];
  }
}

export function WidgetClarificationDrawer({
  batch,
  submitting,
  bottomOffset = 0,
  onResolve,
  onCancel,
}: Props) {
  const [active, setActive] = useState(0);
  const [answers, setAnswers] = useState<Record<string, Answer>>({});

  useEffect(() => {
    setActive(0);
    setAnswers({});
  }, [batch?.items?.map((item) => item.clarificationId).join('|')]);

  const items = batch?.items || [];
  const current = items[active];
  const currentAnswer = current ? answers[current.clarificationId] || { selected: [], text: '' } : null;
  const options = useMemo(() => parseOptions(current?.options), [current?.options]);
  const answered = (id: string) => {
    const a = answers[id];
    return Boolean(a?.selected?.length || a?.text?.trim());
  };
  const allAnswered = items.length > 0 && items.every((item) => answered(item.clarificationId));

  const setAnswer = (id: string, patch: Partial<Answer>) => {
    setAnswers((prev) => ({
      ...prev,
      [id]: {
        selected: patch.selected ?? prev[id]?.selected ?? [],
        text: patch.text ?? prev[id]?.text ?? '',
      },
    }));
  };

  if (!batch || !current || !currentAnswer) return null;

  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: bottomOffset,
        zIndex: 2147482000,
        maxHeight: '70%',
        display: 'flex',
        flexDirection: 'column',
        borderRadius: '16px 16px 0 0',
        borderTop: '1px solid rgba(255,255,255,.55)',
        background: 'rgba(255,255,255,.1)',
        WebkitBackdropFilter: 'blur(18px)',
        backdropFilter: 'blur(18px)',
        boxShadow: '0 -8px 30px rgba(0,0,0,.16)',
        overflow: 'hidden',
      }}
    >
      <div style={{ padding: '10px 14px', borderBottom: '1px solid rgba(0,0,0,.06)', fontSize: 12, fontWeight: 600 }}>
        需要你的确认
        <span style={{ float: 'right', fontWeight: 400, color: '#6b7280' }}>
          {items.filter((i) => answered(i.clarificationId)).length}/{items.length}
        </span>
      </div>
      <div style={{ display: 'flex', gap: 4, padding: '8px 10px', overflowX: 'auto', borderBottom: '1px solid rgba(0,0,0,.06)' }}>
        {items.map((item, i) => (
          <button
            key={item.clarificationId}
            type="button"
            onClick={() => setActive(i)}
            style={{
              border: 'none',
              borderRadius: 6,
              padding: '4px 10px',
              cursor: 'pointer',
              whiteSpace: 'nowrap',
              background: i === active ? '#1677ff' : 'rgba(255,255,255,.55)',
              color: i === active ? '#fff' : '#333',
            }}
          >
            {item.title || item.clarificationId}
            {answered(item.clarificationId) ? ' ✓' : ''}
          </button>
        ))}
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: 14 }}>
        <div style={{ fontWeight: 600, marginBottom: 8 }}>{current.title}</div>
        {current.type === 'confirm' ? (
          <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
            {[
              ['confirmed', '确认'],
              ['cancelled', '取消'],
            ].map(([value, label]) => (
              <button
                key={value}
                type="button"
                onClick={() => setAnswer(current.clarificationId, { selected: [value] })}
                style={{
                  border: `1px solid ${currentAnswer.selected[0] === value ? '#1677ff' : '#d9d9d9'}`,
                  background: currentAnswer.selected[0] === value ? '#e6f4ff' : 'rgba(255,255,255,.7)',
                  borderRadius: 6,
                  padding: '6px 10px',
                  cursor: 'pointer',
                }}
              >
                {label}
              </button>
            ))}
          </div>
        ) : null}
        {current.type === 'choice' && options.length > 0 ? (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
            {options.map((option) => {
              const selected = currentAnswer.selected.includes(option.value);
              return (
                <button
                  key={option.value}
                  type="button"
                  onClick={() =>
                    setAnswer(current.clarificationId, {
                      selected: current.multiple
                        ? selected
                          ? currentAnswer.selected.filter((v) => v !== option.value)
                          : [...currentAnswer.selected, option.value]
                        : [option.value],
                    })
                  }
                  style={{
                    border: `1px solid ${selected ? '#1677ff' : '#d9d9d9'}`,
                    background: selected ? '#e6f4ff' : 'rgba(255,255,255,.7)',
                    borderRadius: 6,
                    padding: '6px 10px',
                    cursor: 'pointer',
                  }}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
        ) : null}
        <textarea
          value={currentAnswer.text}
          onChange={(e) => setAnswer(current.clarificationId, { text: e.target.value })}
          rows={3}
          placeholder="也可以手动输入…"
          style={{ width: '100%', boxSizing: 'border-box', resize: 'vertical' }}
        />
      </div>
      <div style={{ padding: 10, borderTop: '1px solid rgba(0,0,0,.06)', display: 'flex', justifyContent: 'space-between' }}>
        <button type="button" disabled={submitting} onClick={onCancel} style={{ border: '1px solid #ffccc7', color: '#cf1322', background: 'rgba(255,255,255,.7)', borderRadius: 6, padding: '6px 12px' }}>
          停止
        </button>
        <button
          type="button"
          disabled={!allAnswered || submitting}
          onClick={() =>
            onResolve(
              items.map((item) => ({
                clarificationId: item.clarificationId,
                selected: answers[item.clarificationId]?.selected || [],
                text: answers[item.clarificationId]?.text || '',
              })),
            )
          }
          style={{ border: 'none', background: allAnswered ? '#1677ff' : '#bfbfbf', color: '#fff', borderRadius: 6, padding: '6px 16px' }}
        >
          {submitting ? '提交中...' : '提交'}
        </button>
      </div>
    </div>
  );
}

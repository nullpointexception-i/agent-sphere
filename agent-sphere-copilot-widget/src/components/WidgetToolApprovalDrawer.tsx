import { useEffect, useMemo, useState } from 'react';
import type { ToolApprovalBatch } from '../types';

interface Props {
  batch: ToolApprovalBatch | null;
  submitting?: boolean;
  bottomOffset?: number;
  onResolve: (decisions: Array<{ callId: string; decision: string }>) => void;
}

const LABELS: Record<string, string> = {
  DENY: '不允许执行',
  ALLOW_ONCE: '允许执行一次',
  ALLOW_RUN: '本条消息内允许',
  ALLOW_SESSION: '本次会话内允许',
};

function optionsFor(scope?: string) {
  if (scope === 'SESSION') return ['DENY', 'ALLOW_ONCE', 'ALLOW_RUN', 'ALLOW_SESSION'];
  if (scope === 'RUN') return ['DENY', 'ALLOW_ONCE', 'ALLOW_RUN'];
  return ['DENY', 'ALLOW_ONCE'];
}

export function WidgetToolApprovalDrawer({
  batch,
  submitting,
  bottomOffset = 0,
  onResolve,
}: Props) {
  const [active, setActive] = useState(0);
  const [decisions, setDecisions] = useState<Record<string, string>>({});

  useEffect(() => {
    setActive(0);
    setDecisions({});
  }, [batch?.approvalId]);

  const calls = batch?.calls || [];
  const allSelected = calls.length > 0 && calls.every((c) => decisions[c.callId]);
  const current = calls[active];
  const options = useMemo(() => optionsFor(current?.approvalScope), [current?.approvalScope]);

  if (!batch) return null;

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
        <div style={{ padding: '12px 16px', background: 'transparent', borderBottom: '1px solid #f0f0f0' }}>
          <strong>需要确认工具调用</strong>
          <span style={{ marginLeft: 8, fontSize: 12, color: '#ad6800' }}>L2</span>
          <span style={{ float: 'right', fontSize: 12, color: '#8c8c8c' }}>
            {Object.keys(decisions).length}/{calls.length}
          </span>
        </div>
        <div style={{ display: 'flex', gap: 4, padding: '8px 12px', overflowX: 'auto', borderBottom: '1px solid #f0f0f0' }}>
          {calls.map((c, i) => (
            <button
              key={c.callId}
              type="button"
              onClick={() => setActive(i)}
              style={{
                border: 'none',
                borderRadius: 6,
                padding: '4px 10px',
                cursor: 'pointer',
                background: i === active ? '#1677ff' : '#f5f5f5',
                color: i === active ? '#fff' : '#333',
                whiteSpace: 'nowrap',
              }}
            >
              {c.displayName || c.toolName}
              {decisions[c.callId] ? ' ✓' : ''}
            </button>
          ))}
        </div>
        <div style={{ flex: 1, overflow: 'auto', padding: 16 }}>
          {current ? (
            <>
              <div style={{ fontWeight: 600 }}>
                {current.displayName || current.toolName}
                {current.action ? ` · ${current.action}` : ''}
              </div>
              <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 4 }}>
                最多允许：{current.approvalScope === 'SESSION' ? '本次会话' : current.approvalScope === 'RUN' ? '本条消息' : '一次性'}
              </div>
              <details style={{ marginTop: 8 }}>
                <summary style={{ cursor: 'pointer', fontSize: 12 }}>参数</summary>
                <pre style={{ maxHeight: 160, overflow: 'auto', background: '#f5f5f5', padding: 8, borderRadius: 6, fontSize: 11 }}>
                  {current.arguments || ''}
                </pre>
              </details>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
                {options.map((d) => (
                  <button
                    key={d}
                    type="button"
                    onClick={() => setDecisions((prev) => ({ ...prev, [current.callId]: d }))}
                    style={{
                      border: '1px solid ' + (decisions[current.callId] === d ? '#1677ff' : '#d9d9d9'),
                      background: decisions[current.callId] === d ? '#e6f4ff' : '#fff',
                      color: d === 'DENY' ? '#cf1322' : '#333',
                      borderRadius: 6,
                      padding: '6px 10px',
                      cursor: 'pointer',
                    }}
                  >
                    {LABELS[d]}
                  </button>
                ))}
              </div>
            </>
          ) : null}
        </div>
        <div style={{ padding: 12, borderTop: '1px solid #f0f0f0', display: 'flex', justifyContent: 'space-between' }}>
          <button
            type="button"
            disabled={submitting}
            onClick={() => onResolve(calls.map((c) => ({ callId: c.callId, decision: 'DENY' })))}
            style={{ border: '1px solid #ffccc7', color: '#cf1322', background: '#fff', borderRadius: 6, padding: '6px 12px' }}
          >
            全部拒绝
          </button>
          <button
            type="button"
            disabled={!allSelected || submitting}
            onClick={() => onResolve(calls.map((c) => ({ callId: c.callId, decision: decisions[c.callId] })))}
            style={{ border: 'none', background: allSelected ? '#1677ff' : '#bfbfbf', color: '#fff', borderRadius: 6, padding: '6px 16px' }}
          >
            {submitting ? '提交中...' : '提交'}
          </button>
        </div>
    </div>
  );
}

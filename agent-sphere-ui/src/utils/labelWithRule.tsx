import React from 'react';

export function labelWithRule(
  label: React.ReactNode,
  rule: React.ReactNode,
): React.ReactNode {
  return (
    <span>
      {label}{' '}
      <span style={{ fontSize: 12, color: '#999', fontWeight: 400 }}>
        {rule}
      </span>
    </span>
  );
}

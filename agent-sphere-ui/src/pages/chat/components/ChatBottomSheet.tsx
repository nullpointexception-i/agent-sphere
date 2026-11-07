import type { CSSProperties, ReactNode } from 'react';

interface Props {
  open: boolean;
  title?: ReactNode;
  extra?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  bottomOffset?: number;
  maxHeight?: number | string;
}

/**
 * Floating layer at the bottom of the chat message area. Positioning is handled by the parent
 * chatStage's absolute container: the layer's bottom sits on the input area's top divider
 * (above the read/write mode switch) and covers only the message area.
 */
export default function ChatBottomSheet({
  open,
  title,
  extra,
  children,
  footer,
  bottomOffset = 0,
  maxHeight = 'min(70vh, 640px)',
}: Props) {
  if (!open) return null;
  const panelStyle: CSSProperties = {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: bottomOffset,
    zIndex: 30,
    maxHeight,
    display: 'flex',
    flexDirection: 'column',
    borderTop: '1px solid rgba(255,255,255,0.55)',
    borderRadius: '16px 16px 0 0',
    background: 'rgba(255,255,255,0.1)',
    WebkitBackdropFilter: 'blur(18px)',
    backdropFilter: 'blur(18px)',
    boxShadow: '0 -8px 30px rgba(0,0,0,0.12)',
    overflow: 'hidden',
  };
  return (
    <div style={panelStyle}>
      <div
        style={{
          padding: '12px 18px 10px',
          borderBottom: '1px solid rgba(0,0,0,0.06)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <div style={{ flex: 1, minWidth: 0 }}>{title}</div>
        {extra}
      </div>
      <div
        style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '8px 18px' }}
      >
        {children}
      </div>
      {footer ? (
        <div
          style={{
            padding: '10px 18px',
            borderTop: '1px solid rgba(0,0,0,0.06)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 8,
          }}
        >
          {footer}
        </div>
      ) : null}
    </div>
  );
}

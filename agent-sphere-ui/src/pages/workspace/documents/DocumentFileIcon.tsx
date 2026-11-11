import { type DocumentKind, documentKindLabel } from './documentModel';

const COLORS: Record<DocumentKind, string> = {
  markdown: '#5b8def',
  text: '#8a93a6',
  pdf: '#e05a5a',
  word: '#4a7bd0',
  other: '#9b95b3',
};

export function DocumentFileIcon({
  kind,
  size = 64,
}: {
  kind: DocumentKind;
  size?: number;
}) {
  const color = COLORS[kind] ?? COLORS.other;
  return (
    <span
      style={{
        display: 'inline-flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 4,
        width: size,
      }}
      aria-hidden
    >
      <svg viewBox="0 0 64 74" width={size} height={size * 1.16}>
        <title>{documentKindLabel(kind)}</title>
        <path
          d="M9 2h33l13 13v53a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4V6a4 4 0 0 1 4-4z"
          fill={color}
        />
        <path d="M42 2v13h13z" fill="rgba(255,255,255,0.34)" />
        <rect
          x="14"
          y="31"
          width="32"
          height="3.5"
          rx="1.75"
          fill="rgba(255,255,255,0.85)"
        />
        <rect
          x="14"
          y="41"
          width="26"
          height="3.5"
          rx="1.75"
          fill="rgba(255,255,255,0.68)"
        />
        <rect
          x="14"
          y="51"
          width="30"
          height="3.5"
          rx="1.75"
          fill="rgba(255,255,255,0.5)"
        />
      </svg>
      <span
        style={{
          color,
          fontSize: 10,
          fontWeight: 700,
          letterSpacing: 0.4,
        }}
      >
        {documentKindLabel(kind)}
      </span>
    </span>
  );
}

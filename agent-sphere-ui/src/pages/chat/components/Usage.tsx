import { Tooltip, Typography } from 'antd';

export const SESSION_USAGE_REFRESH_EVENT = 'agent:session-usage-refresh';

export interface UsageData {
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  cacheHitTokens?: number | null;
  cacheMissTokens?: number | null;
}

/** Thousands abbreviation: 1234 → 1.2k; under a thousand shows the raw value. */
export function formatTokens(n?: number | null): string {
  if (n == null) return '-';
  if (n >= 1000) {
    const k = n / 1000;
    return `${k >= 100 ? Math.round(k) : Math.round(k * 10) / 10}k`;
  }
  return String(n);
}

/** Current context usage as a share of the model's input limit (%): latest prompt / maxInputTokens; null when there is no valid denominator. */
export function contextUsagePercent(
  latestPrompt?: number | null,
  maxInput?: number | null,
): number | null {
  if (latestPrompt == null || maxInput == null || maxInput <= 0) return null;
  return Math.round((latestPrompt * 1000) / maxInput) / 10;
}

/** Cache hit rate (%): hit / (hit+miss); null when there is no base. */
export function cacheHitRate(u?: UsageData): number | null {
  if (!u) return null;
  const hit = u.cacheHitTokens || 0;
  const miss = u.cacheMissTokens || 0;
  const denom = hit + miss;
  if (denom <= 0) return null;
  return Math.round((hit * 1000) / denom) / 10;
}

const row = (label: string, value?: number | null) => (
  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
    <span style={{ opacity: 0.75 }}>{label}</span>
    <span style={{ fontVariantNumeric: 'tabular-nums' }}>
      {formatTokens(value)}
    </span>
  </div>
);

/** Usage chip (shared by chat-area iteration / run rows, run records, etc.); renders nothing when there is no usage. */
export default function UsageChip({ usage }: { usage?: UsageData }) {
  if (!usage || !usage.totalTokens) return null;
  const rate = cacheHitRate(usage);
  return (
    <Tooltip
      title={
        <div style={{ fontSize: 12 }}>
          {row('Prompt', usage.promptTokens)}
          {row('Completion', usage.completionTokens)}
          {row('Total', usage.totalTokens)}
          {row('Cache hit', usage.cacheHitTokens)}
          {row('Cache miss', usage.cacheMissTokens)}
          {rate != null ? (
            <div
              style={{
                marginTop: 4,
                borderTop: '1px solid rgba(255,255,255,0.25)',
              }}
            >
              {row('Cache rate', Math.round(rate))}
            </div>
          ) : null}
        </div>
      }
    >
      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
        ⛓ {formatTokens(usage.totalTokens)} tokens
        {rate != null ? ` · cache ${rate}%` : ''}
      </Typography.Text>
    </Tooltip>
  );
}

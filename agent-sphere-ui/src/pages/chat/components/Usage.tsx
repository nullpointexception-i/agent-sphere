import { Tooltip, Typography } from 'antd';

export interface UsageData {
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  cacheHitTokens?: number | null;
  cacheMissTokens?: number | null;
}

/** 千分位缩写：1234 → 1.2k；不足千显示原文。 */
export function formatTokens(n?: number | null): string {
  if (n == null) return '-';
  if (n >= 1000) {
    const k = n / 1000;
    return `${k >= 100 ? Math.round(k) : Math.round(k * 10) / 10}k`;
  }
  return String(n);
}

/** 缓存命中率(%)：hit / (hit+miss)；无基数返回 null。 */
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

/** 用量小标（聊天区 iteration / run 行、run 记录等共用）；无用量时不渲染。 */
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

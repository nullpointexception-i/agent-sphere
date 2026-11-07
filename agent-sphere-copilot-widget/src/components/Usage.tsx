/**
 * Usage display (ported from the main site's UmiJS Usage.tsx, with antd removed).
 * Thousands abbreviation + cache hit rate + hover details (title attribute instead of Tooltip).
 */
import type { UsageData } from '../types';

export type { UsageData };

/** Thousands abbreviation: 1234 -> 1.2k; below one thousand shows the original value. */
export function formatTokens(n?: number | null): string {
  if (n == null) return '-';
  if (n >= 1000) {
    const k = n / 1000;
    return `${k >= 100 ? Math.round(k) : Math.round(k * 10) / 10}k`;
  }
  return String(n);
}

/** Cache hit rate (%): hit / (hit+miss); returns null when there is no denominator. */
export function cacheHitRate(u?: UsageData): number | null {
  if (!u) return null;
  const hit = u.cacheHitTokens || 0;
  const miss = u.cacheMissTokens || 0;
  const denom = hit + miss;
  if (denom <= 0) return null;
  return Math.round((hit * 1000) / denom) / 10;
}

/** Usage badge; renders nothing when there is no usage. */
export default function UsageChip({ usage }: { usage?: UsageData }) {
  if (!usage || !usage.totalTokens) return null;
  const rate = cacheHitRate(usage);
  const tip = [
    `Prompt ${formatTokens(usage.promptTokens)}`,
    `Completion ${formatTokens(usage.completionTokens)}`,
    `Total ${formatTokens(usage.totalTokens)}`,
    `Cache hit ${formatTokens(usage.cacheHitTokens)}`,
    `Cache miss ${formatTokens(usage.cacheMissTokens)}`,
    rate != null ? `Cache rate ${rate}%` : '',
  ]
    .filter(Boolean)
    .join(' · ');
  return (
    <span className="aw-usage-chip" title={tip}>
      ⛓ {formatTokens(usage.totalTokens)} tokens
      {rate != null ? ` · cache ${rate}%` : ''}
    </span>
  );
}

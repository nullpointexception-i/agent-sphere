import { Line } from '@ant-design/plots';
import { Button, Card, Empty, Segmented, Space, Spin, Statistic, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { formatTokens } from '../Usage';

interface UsagePoint {
  id: number;
  interactionType: string;
  modelName?: string;
  createdAt?: string;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  cacheHitTokens?: number;
  cacheMissTokens?: number;
}

interface UsageTrendProps {
  open: boolean;
  runId: number | null;
  sessionId: number | null;
}

const LIMIT = 300;
const REFRESH_MS = 5_000;

const METRICS = [
  { name: '上下文', key: 'promptTokens' },
  { name: '缓存命中', key: 'cacheHitTokens' },
  { name: '缓存未命中', key: 'cacheMissTokens' },
  { name: '输出', key: 'completionTokens' },
  { name: '总量', key: 'totalTokens' },
] as const;

export default function UsageTrend({
  open,
  runId,
  sessionId,
}: UsageTrendProps) {
  const [points, setPoints] = useState<UsagePoint[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [zoomed, setZoomed] = useState(false);
  const [mode, setMode] = useState<'main' | 'all'>('main');
  const [chartKey, setChartKey] = useState(0);
  const [collapsed, setCollapsed] = useState(false);
  const lastIdRef = useRef(0);
  const inFlightRef = useRef(false);
  const zoomedRef = useRef(false);
  const domainRef = useRef({ min: 1, max: 0 });

  const fetchSeries = useCallback(
    async (reset: boolean) => {
      if (runId == null || sessionId == null || inFlightRef.current) return;
      inFlightRef.current = true;
      if (reset) {
        setLoading(true);
      }
      try {
        const res = await agentApi.activities.usageSeries(
          runId,
          sessionId,
          reset ? 0 : lastIdRef.current,
          LIMIT,
        );
        const incoming: UsagePoint[] = res.points || [];
        if (reset) {
          setPoints(incoming.slice(-LIMIT));
          setHasMore(Boolean(res.hasMore));
          lastIdRef.current = incoming.length
            ? Math.max(...incoming.map((p) => Number(p.id)))
            : 0;
        } else if (incoming.length) {
          setPoints((prev) => {
            const map = new Map<number, UsagePoint>(
              prev.map((p) => [p.id, p]),
            );
            incoming.forEach((p) => map.set(p.id, p));
            return [...map.values()]
              .sort((a, b) => a.id - b.id)
              .slice(-LIMIT);
          });
          lastIdRef.current = Math.max(
            lastIdRef.current,
            ...incoming.map((p) => Number(p.id)),
          );
        }
      } catch {
        // 轮询失败保留旧数据
      } finally {
        inFlightRef.current = false;
        setLoading(false);
      }
    },
    [runId, sessionId],
  );

  useEffect(() => {
    if (!open || runId == null || sessionId == null) return;
    setPoints([]);
    setHasMore(false);
    setZoomed(false);
    zoomedRef.current = false;
    setChartKey((k) => k + 1);
    lastIdRef.current = 0;
    void fetchSeries(true);
    const timer = window.setInterval(() => {
      if (!zoomedRef.current) {
        void fetchSeries(false);
      }
    }, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [open, runId, sessionId, fetchSeries]);

  const mainPoints = useMemo(
    () => points.filter((p) => p.interactionType === 'CHAT_REPLY'),
    [points],
  );
  const visiblePoints = mode === 'main' ? mainPoints : points;
  const latest = mainPoints[mainPoints.length - 1];
  const peak = mainPoints.reduce(
    (max, p) => Math.max(max, Number(p.promptTokens) || 0),
    0,
  );
  const average = mainPoints.length
    ? Math.round(
        mainPoints.reduce((sum, p) => sum + (Number(p.promptTokens) || 0), 0) /
          mainPoints.length,
      )
    : 0;
  const cacheRate =
    latest && Number(latest.promptTokens)
      ? Math.round(
          ((Number(latest.cacheHitTokens) || 0) /
            Number(latest.promptTokens)) *
            100,
        )
      : null;

  const chartData = useMemo(() => {
    const seqById = new Map<number, number>();
    points.forEach((p, i) => seqById.set(p.id, i + 1));
    return visiblePoints.flatMap((p) =>
      METRICS.map((m) => ({
        x: seqById.get(p.id) ?? 0,
        y: Number(p[m.key]) || 0,
        series: m.name,
        point: p,
      })),
    );
  }, [visiblePoints, points]);

  useEffect(() => {
    const seqById = new Map<number, number>();
    points.forEach((p, i) => seqById.set(p.id, i + 1));
    const xs = visiblePoints.map((p) => seqById.get(p.id) ?? 0);
    domainRef.current = {
      min: xs.length ? Math.min(...xs) : 1,
      max: xs.length ? Math.max(...xs) : 0,
    };
  }, [points, visiblePoints]);

  const onReady = useCallback((chart: any) => {
    chart.on('brush:filter', (e: any) => {
      const selection = e?.data?.selection?.[0];
      const { min, max } = domainRef.current;
      if (!Array.isArray(selection) || max <= 0) {
        zoomedRef.current = false;
        setZoomed(false);
        return;
      }
      const isFull = selection[0] <= min && selection[1] >= max;
      zoomedRef.current = !isFull;
      setZoomed(!isFull);
    });
  }, []);

  const resetZoom = () => {
    zoomedRef.current = false;
    setZoomed(false);
    setChartKey((k) => k + 1);
    void fetchSeries(true);
  };

  const toggleCollapsed = () => {
    if (!collapsed) {
      zoomedRef.current = false;
      setZoomed(false);
    }
    setCollapsed((v) => !v);
  };

  const changeMode = (value: string | number) => {
    setMode(value as 'main' | 'all');
    zoomedRef.current = false;
    setZoomed(false);
    setChartKey((k) => k + 1);
  };

  return (
    <Card
      size="small"
      title="Token 趋势"
      style={{ marginBottom: 12 }}
      extra={
        <Space size={8}>
          <Segmented
            size="small"
            value={mode}
            onChange={changeMode}
            options={[
              { label: '主对话', value: 'main' },
              { label: '全部模型交互', value: 'all' },
            ]}
          />
          <Button size="small" disabled={!zoomed} onClick={resetZoom}>
            重置缩放
          </Button>
          <Button
            size="small"
            type="text"
            onClick={toggleCollapsed}
          >
            {collapsed ? '展开' : '收起'}
          </Button>
        </Space>
      }
    >
      {!collapsed && (
        <>
          <Space size={24} style={{ marginBottom: 8 }}>
            <Statistic
              title="最新上下文"
              value={formatTokens(latest?.promptTokens)}
              valueStyle={{ fontSize: 14 }}
            />
            <Statistic
              title="峰值"
              value={formatTokens(peak)}
              valueStyle={{ fontSize: 14 }}
            />
            <Statistic
              title="平均"
              value={formatTokens(average)}
              valueStyle={{ fontSize: 14 }}
            />
            <Statistic
              title="缓存命中率"
              value={cacheRate == null ? '-' : `${cacheRate}%`}
              valueStyle={{ fontSize: 14 }}
            />
          </Space>
          {loading && points.length === 0 ? (
            <div style={{ height: 240, display: 'grid', placeItems: 'center' }}>
              <Spin />
            </div>
          ) : points.length === 0 ? (
            <Empty description="暂无交互数据" />
          ) : (
            <Line
              key={chartKey}
              data={chartData}
              xField="x"
              yField="y"
              colorField="series"
              height={240}
              autoFit
              legend={{ color: { position: 'top' } }}
              axis={{ x: { title: '交互序号' }, y: { title: 'tokens' } }}
              interactions={[{ type: 'brushXFilter' }] as any}
              onReady={onReady}
            />
          )}
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
            仅显示最近 {LIMIT} 条{hasMore ? '（还有更早数据）' : ''}
            {zoomed ? ' · 已放大，自动刷新暂停' : ''}
          </Typography.Text>
        </>
      )}
    </Card>
  );
}

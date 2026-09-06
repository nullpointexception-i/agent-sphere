import { RightOutlined } from '@ant-design/icons';
import { Tag } from 'antd';
import { useRef, useState } from 'react';
import SubAgentDetailModal from './SubAgentDetailModal';
import type { SubAgentLive, SubAgentTimelineItem } from './subAgentTypes';

interface SubAgentDockProps {
  /** 实时聚合的子 Agent（SSE 生成）。 */
  live: SubAgentLive[];
  /** 历史子 Agent 元信息（sub-agent-runs 接口）。 */
  historical?: { id: number; displayName: string; status?: string }[];
  /** 选中历史项时拉取 timeline。 */
  loadTimeline: (id: number) => Promise<SubAgentTimelineItem[]>;
}

type DockItem = {
  key: string;
  label: string;
  status?: string;
  isLive: boolean;
  live?: SubAgentLive;
};

/** 子 Agent 沉底栏：chat 底部横向滑动选择；点击弹出时间线详情卡片。 */
export default function SubAgentDock({
  live,
  historical = [],
  loadTimeline,
}: SubAgentDockProps) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);
  const [openLive, setOpenLive] = useState<SubAgentLive | null>(null);
  const [timeline, setTimeline] = useState<SubAgentTimelineItem[]>([]);
  const [title, setTitle] = useState('');

  const onWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    if (scrollRef.current) scrollRef.current.scrollLeft += e.deltaY;
  };

  const items: DockItem[] = [
    ...live.map((l) => ({
      key: l.key,
      label: l.name,
      status: l.status || 'RUNNING',
      isLive: true,
      live: l,
    })),
    // 历史项与仍在 live 的同一 sub-agent run（subAgentRunId 相同）去重：只展示 live
    ...historical
      .filter(
        (h) =>
          !live.some(
            (l) => l.subAgentRunId != null && Number(l.subAgentRunId) === h.id,
          ),
      )
      .map((h) => ({
        key: `h-${h.id}`,
        label: h.displayName,
        status: h.status,
        isLive: false,
      })),
  ];

  const openItem = (it: DockItem) => {
    setTitle(it.label);
    if (it.isLive && it.live) {
      setOpenLive(it.live);
      setTimeline([]);
    } else {
      setOpenLive(null);
      setTimeline([]);
      const id = Number(it.key.replace('h-', ''));
      void loadTimeline(id)
        .then((tl) => setTimeline(tl || []))
        .catch(() => setTimeline([]));
    }
    setOpen(true);
  };

  return (
    <>
      {items.length === 0 ? null : (
        <div
          style={{
            borderTop: '1px solid #f0f0f0',
            padding: '6px 12px',
            background: '#fafafa',
          }}
        >
          <div
            style={{
              fontSize: 11,
              color: '#bfbfbf',
              marginBottom: 4,
              fontWeight: 500,
            }}
          >
            子 Agent（Sub-Agent）
          </div>
          <div
            ref={scrollRef}
            onWheel={onWheel}
            style={{
              display: 'flex',
              gap: 8,
              overflowX: 'auto',
              scrollbarWidth: 'thin',
              paddingBottom: 4,
            }}
          >
            {items.map((it) => (
              <button
                key={it.key}
                type="button"
                onClick={() => openItem(it)}
                style={{
                  cursor: 'pointer',
                  border: '1px solid #e8e8e8',
                  borderRadius: 6,
                  background: '#fff',
                  padding: '4px 10px',
                  whiteSpace: 'nowrap',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  fontSize: 12,
                  color: '#595959',
                  maxWidth: 220,
                  flexShrink: 0,
                  overflow: 'hidden',
                }}
              >
                <span style={{ flexShrink: 0 }}>⚙️</span>
                <span
                  style={{
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                    minWidth: 0,
                  }}
                >
                  {it.label}
                </span>
                <Tag
                  color={
                    it.status === 'COMPLETED'
                      ? 'success'
                      : it.status === 'FAILED' || it.status === 'TIMEOUT'
                        ? 'error'
                        : 'processing'
                  }
                  style={{ margin: 0, fontSize: 10 }}
                >
                  {it.status || 'RUNNING'}
                </Tag>
                <RightOutlined style={{ fontSize: 9, color: '#bfbfbf' }} />
              </button>
            ))}
          </div>
        </div>
      )}
      <SubAgentDetailModal
        open={open}
        title={title}
        live={openLive}
        timeline={timeline}
        onClose={() => setOpen(false)}
      />
    </>
  );
}

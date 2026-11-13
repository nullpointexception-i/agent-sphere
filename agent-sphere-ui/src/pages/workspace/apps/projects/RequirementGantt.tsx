import { Empty, Tag, Tooltip } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import {
  type PointerEvent as ReactPointerEvent,
  useEffect,
  useRef,
  useState,
} from 'react';
import {
  REQUIREMENT_STATUS_LABEL_KEYS,
  REQUIREMENT_STATUS_META,
} from '@/constants/project';
import type { RequirementVO } from '@/services/agentSphere/project';
import { tw } from '../../i18n';
import { enumLabel } from '../labels';
import { useAppStyles } from '../style';

const DAY_WIDTH = 30;
const LABEL_WIDTH = 220;

interface GanttRow {
  requirement: RequirementVO;
  start: Dayjs;
  end: Dayjs;
}

function fallbackStart(requirement: RequirementVO) {
  if (requirement.startDate) return dayjs(requirement.startDate);
  if (requirement.dueDate) return dayjs(requirement.dueDate);
  return dayjs(requirement.createdAt || undefined);
}

type DragMode = 'move' | 'start' | 'end';

interface DragState {
  row: GanttRow;
  mode: DragMode;
  startX: number;
  delta: number;
  minDelta: number;
  maxDelta: number;
}

export function RequirementGantt({
  requirements,
  onMove,
}: {
  requirements: RequirementVO[];
  onMove?: (
    requirement: RequirementVO,
    startDate: string,
    dueDate: string,
  ) => void | Promise<void>;
}) {
  const { styles } = useAppStyles();
  const [drag, setDrag] = useState<DragState | null>(null);
  const dragRef = useRef<DragState | null>(null);

  useEffect(() => {
    if (!drag) return;
    const handlePointerMove = (event: PointerEvent) => {
      const current = dragRef.current;
      if (!current) return;
      const delta = Math.round((event.clientX - current.startX) / DAY_WIDTH);
      const clamped = Math.max(
        current.minDelta,
        Math.min(current.maxDelta, delta),
      );
      const next = { ...current, delta: clamped };
      dragRef.current = next;
      setDrag(next);
    };
    const handlePointerUp = () => {
      const current = dragRef.current;
      if (current && current.delta !== 0) {
        const start =
          current.mode === 'end'
            ? current.row.start
            : current.row.start.add(current.delta, 'day');
        const end =
          current.mode === 'start'
            ? current.row.end
            : current.row.end.add(current.delta, 'day');
        void onMove?.(
          current.row.requirement,
          start.format('YYYY-MM-DD'),
          end.format('YYYY-MM-DD'),
        );
      }
      dragRef.current = null;
      setDrag(null);
    };
    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', handlePointerUp);
    return () => {
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', handlePointerUp);
    };
  }, [Boolean(drag), onMove]);

  const rows: GanttRow[] = requirements
    .map((requirement) => {
      const start = fallbackStart(requirement).startOf('day');
      const end = requirement.dueDate
        ? dayjs(requirement.dueDate).startOf('day')
        : start;
      return {
        requirement,
        start,
        end: end.isBefore(start) ? start : end,
      };
    })
    .filter((row) => row.start.isValid())
    .sort((left, right) => left.start.valueOf() - right.start.valueOf());

  if (rows.length === 0) {
    return (
      <Empty
        description={tw('projects.ganttEmpty', 'No scheduled requirements')}
      />
    );
  }

  const rangeStart = rows
    .reduce(
      (earliest, row) => (row.start.isBefore(earliest) ? row.start : earliest),
      rows[0].start,
    )
    .subtract(2, 'day');
  const rangeEnd = rows
    .reduce(
      (latest, row) => (row.end.isAfter(latest) ? row.end : latest),
      rows[0].end,
    )
    .add(2, 'day');
  const dayCount = rangeEnd.diff(rangeStart, 'day') + 1;
  const timelineWidth = dayCount * DAY_WIDTH;
  const days = Array.from({ length: dayCount }, (_, index) =>
    rangeStart.add(index, 'day'),
  );

  return (
    <div className={styles.gantt}>
      <div className={styles.ganttHead}>
        <div className={styles.ganttLabelHead}>
          {tw('projects.requirements', 'Requirements')}
        </div>
        <div className={styles.ganttTimeline} style={{ width: timelineWidth }}>
          {days.map((day) => (
            <div
              className={styles.ganttDay}
              key={day.format('YYYY-MM-DD')}
              style={{ width: DAY_WIDTH }}
            >
              <span className={styles.ganttDayMonth}>
                {day.date() === 1 || day.isSame(rangeStart, 'day')
                  ? day.format('MM月')
                  : ''}
              </span>
              <span>{day.format('DD')}</span>
            </div>
          ))}
        </div>
      </div>
      <div className={styles.ganttBody}>
        {rows.map((row) => {
          const offset = row.start.diff(rangeStart, 'day');
          const span = row.end.diff(row.start, 'day') + 1;
          const meta = REQUIREMENT_STATUS_META[row.requirement.status];
          const isDragging = drag?.row.requirement.id === row.requirement.id;
          const dragDelta = isDragging ? drag.delta : 0;
          const leftDays =
            drag?.mode === 'start' || drag?.mode === 'move'
              ? offset + dragDelta
              : offset;
          const spanDays =
            drag?.mode === 'start'
              ? span - dragDelta
              : drag?.mode === 'end'
                ? span + dragDelta
                : span;
          const beginDrag = (
            event: ReactPointerEvent<HTMLDivElement>,
            mode: DragMode,
          ) => {
            event.preventDefault();
            event.stopPropagation();
            let minDelta = -offset;
            let maxDelta = dayCount - (offset + span);
            if (mode === 'start') {
              minDelta = -offset;
              maxDelta = span - 1;
            } else if (mode === 'end') {
              minDelta = -(span - 1);
              maxDelta = dayCount - (offset + span);
            }
            const next = {
              row,
              mode,
              startX: event.clientX,
              delta: 0,
              minDelta,
              maxDelta,
            };
            dragRef.current = next;
            setDrag(next);
          };
          return (
            <div className={styles.ganttRow} key={row.requirement.id}>
              <div className={styles.ganttLabel} style={{ width: LABEL_WIDTH }}>
                <div className={styles.ganttLabelTitle}>
                  {row.requirement.title}
                </div>
                <div className={styles.ganttLabelMeta}>
                  <Tag>{row.requirement.requirementKey}</Tag>
                </div>
              </div>
              <div
                className={styles.ganttTimeline}
                style={{
                  width: timelineWidth,
                  backgroundSize: `${DAY_WIDTH}px 100%`,
                }}
              >
                <Tooltip
                  title={
                    <span>
                      {row.requirement.title}
                      <br />
                      {tw('projects.dateRange', 'Date range')}:{' '}
                      {row.start.format('YYYY-MM-DD')} ~{' '}
                      {row.end.format('YYYY-MM-DD')}
                      <br />
                      {tw('projects.status', 'Status')}:{' '}
                      {enumLabel(
                        REQUIREMENT_STATUS_LABEL_KEYS,
                        row.requirement.status,
                      )}
                    </span>
                  }
                >
                  <div
                    className={`${styles.ganttBar} ${
                      isDragging ? styles.ganttBarDragging : ''
                    }`}
                    style={{
                      left: leftDays * DAY_WIDTH + 2,
                      width: Math.max(DAY_WIDTH - 4, spanDays * DAY_WIDTH - 4),
                      background: meta?.color || '#8b86a0',
                    }}
                    onPointerDown={(event) => beginDrag(event, 'move')}
                  >
                    {span > 2 ? row.requirement.title : null}
                    <div
                      className={`${styles.ganttBarHandle} ${styles.ganttBarHandleLeft}`}
                      onPointerDown={(event) => beginDrag(event, 'start')}
                    />
                    <div
                      className={`${styles.ganttBarHandle} ${styles.ganttBarHandleRight}`}
                      onPointerDown={(event) => beginDrag(event, 'end')}
                    />
                  </div>
                </Tooltip>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

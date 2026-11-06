import { useCallback, useEffect, useRef, useState } from 'react';

/** 轮询间隔：长任务每处理完一批就写一次进度，1 秒足够跟上且最省 */
const POLL_INTERVAL_MS = 1000;

const TERMINAL_STATUS = ['SUCCESS', 'FAILED', 'SKIPPED'];

/**
 * 轮询一条「长耗时后台任务」的执行记录，直到它落到终态。
 *
 * <p>会话清理（session-cleanup）与 Skill 自动同步（skill sync-now）都是同一形状：
 * 提交接口立刻返回 `status=RUNNING` 的记录，真正的活在后台跑，前端只能靠轮询拿进度。
 * 这里把逻辑抽出来给两处共用。
 *
 * <p>记录可能永远停在 RUNNING（进程崩了），后端用 `stale` 标出来，这里据此停止轮询 ——
 * 否则就是一条永不停止的定时器。
 *
 * @param runId 记录 id；null 表示不轮询（面板关闭）
 * @param fetchOne 取单条记录的请求
 * @param onFinish 落到终态（含 stale）时回调，用于刷新列表
 */
export function useRecordedTask<T extends { status: string; stale?: boolean }>(
  runId: number | null,
  fetchOne: (id: number) => Promise<T>,
  onFinish?: (record: T) => void,
) {
  const [record, setRecord] = useState<T | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stoppedRef = useRef(false);
  // 回调放 ref：调用方每次渲染都会传新的函数箭头，放 deps 会让 effect 每帧重跑
  const onFinishRef = useRef(onFinish);
  onFinishRef.current = onFinish;

  const stop = useCallback(() => {
    stoppedRef.current = true;
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  useEffect(() => {
    stoppedRef.current = false;
    if (runId == null) {
      setRecord(null);
      return;
    }

    let cancelled = false;
    const tick = async () => {
      try {
        const next = await fetchOne(runId);
        if (cancelled || stoppedRef.current) return;
        setRecord(next);
        if (TERMINAL_STATUS.includes(next.status) || next.stale) {
          stoppedRef.current = true;
          onFinishRef.current?.(next);
          return;
        }
      } catch {
        // 单次轮询失败不终止：下一拍重试，避免网络抖动把进行中的任务显示成结束
        if (cancelled || stoppedRef.current) return;
      }
      if (!cancelled && !stoppedRef.current) {
        timerRef.current = setTimeout(tick, POLL_INTERVAL_MS);
      }
    };

    tick();
    return () => {
      cancelled = true;
      stop();
    };
    // fetchOne 由调用方内联（如 (id) => agentApi.skill.syncRun(id)），每次渲染都是新引用，
    // 因此刻意不进依赖数组：真正变化的只有 runId。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, stop]);

  return {
    record,
    stop,
    running: !!record && record.status === 'RUNNING' && !record.stale,
  };
}

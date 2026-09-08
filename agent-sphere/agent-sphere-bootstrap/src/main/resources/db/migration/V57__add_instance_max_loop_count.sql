-- ============================================================
-- V57：实例级最大循环次数（max_loop_count）
-- 单次 run 的最大循环上限。NULL / 0 = 未配置（走系统
-- runner.max-loop-count；任务触发 run 另有 task-max-loop-count）。
-- 配置值（>0）在执行期最高优先，覆盖任务/系统上限。
-- ============================================================

ALTER TABLE agent_instance ADD COLUMN max_loop_count INTEGER NULL;

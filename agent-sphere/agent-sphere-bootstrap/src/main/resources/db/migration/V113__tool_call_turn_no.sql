-- ============================================================
-- V113：工具调用与 LLM 轮次的显式关联（turn_no）
--
-- 背景：wait/澄清恢复时 HistoryLoader 只能按 created_at 时间窗口把 tool 记录配对到
-- 各轮 CHAT_REPLY。这个启发式在「工具调用落在下一轮开始的同一秒」时必然出错
-- （线上 run 2810：tool 03:54:51.000042 vs turn17 03:54:51.842411），
-- 会把两条调用并进一条 assistant，模型端直接 400。
--
-- agent_llm_interaction_record.turn_no 已存在（= run.loop_count），本迁移把它也写到
-- tool 记录上，读取侧可以直接按 (run_id, turn_no) 精确分组，不再依赖时间。
--
-- 历史行保持 NULL，读取侧回退到毫秒时间窗口（见 HistoryLoader）。
-- 加列不带默认值：PG11+ 只改元数据，不重写大表。
-- ============================================================

ALTER TABLE agent_tool_call_record ADD COLUMN IF NOT EXISTS turn_no INT;

CREATE INDEX IF NOT EXISTS idx_tool_call_run_turn
    ON agent_tool_call_record (run_id, turn_no)
    WHERE turn_no IS NOT NULL
      AND delete_flag = 0;

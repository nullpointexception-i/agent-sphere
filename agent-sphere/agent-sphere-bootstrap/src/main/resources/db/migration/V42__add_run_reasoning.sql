-- ============================================================
-- agent_run.reasoning — 累积的模型推理/thinking 文本（history 回看用）
-- ============================================================
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS reasoning TEXT;

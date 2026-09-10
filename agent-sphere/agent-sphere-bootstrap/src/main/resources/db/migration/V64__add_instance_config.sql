-- ============================================================
-- V64：agent 实例级采样配置（agent_instance.config JSONB）
-- 与 agent_completions.config 同款约定：JSONB 列 + 实体 String 字段
-- ============================================================
ALTER TABLE agent_instance
    ADD COLUMN IF NOT EXISTS config JSONB;
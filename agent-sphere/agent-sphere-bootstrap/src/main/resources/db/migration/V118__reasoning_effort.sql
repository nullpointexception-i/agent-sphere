-- ============================================================
-- V118：统一思考强度（reasoning effort）
--
-- 1) 厂商/路由维度配置思考强度能力（阈值默认在 application.yml，数据库只做覆盖）
-- 2) run 固化本次选择的档位与 Qwen 思考预算，恢复/重试不漂移
-- 3) 交互记录保留 finish_reason，截断（length/content_filter）可审计
-- 4) 清理实例配置里已废弃的 budget_tokens（DeepSeek/OpenAI/Qwen/GLM 均不支持；
--    Anthropic 的 budget_tokens 语义也不同，后续由专属策略处理）
-- ============================================================

ALTER TABLE agent_model_route
    ADD COLUMN IF NOT EXISTS reasoning_supported BOOLEAN,
    ADD COLUMN IF NOT EXISTS reasoning_mode VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reasoning_levels JSONB,
    ADD COLUMN IF NOT EXISTS reasoning_answer_reserve_ratio NUMERIC;

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reasoning_budget_tokens INTEGER;

ALTER TABLE agent_llm_interaction_record
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(32);

UPDATE agent_instance
SET config = config - 'budget_tokens'
WHERE config ? 'budget_tokens';

-- ============================================================
-- V65：LLM interaction 用量落库
-- usage 存归一化 TokenUsage JSON（供应商无关），
-- 5 个标量冗余列直接供 SQL 聚合（SUM/AVG），免 JSON 解析
-- ============================================================
ALTER TABLE agent_llm_interaction_record
    ADD COLUMN IF NOT EXISTS usage             JSONB,
    ADD COLUMN IF NOT EXISTS prompt_tokens     INT,
    ADD COLUMN IF NOT EXISTS completion_tokens INT,
    ADD COLUMN IF NOT EXISTS total_tokens      INT,
    ADD COLUMN IF NOT EXISTS cache_hit_tokens  INT,
    ADD COLUMN IF NOT EXISTS cache_miss_tokens INT;
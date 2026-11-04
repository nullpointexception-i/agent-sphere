-- ============================================================
-- V119：模型路由的最大输入/输出改为显式必填
--
-- 聊天输出上限改为：实例 config.max_tokens > route.max_output_tokens > 16k；
-- 路由字段缺失会让运行时落到隐式默认值，和 UI/配置不一致。
-- 这里回填存量 NULL，并给列加默认值，保证后续插入也安全。
-- ============================================================

ALTER TABLE agent_model_route
    ALTER COLUMN max_input_tokens SET DEFAULT 128000,
    ALTER COLUMN max_output_tokens SET DEFAULT 16000;

UPDATE agent_model_route
SET max_input_tokens = COALESCE(max_input_tokens, 128000),
    max_output_tokens = COALESCE(max_output_tokens, 16000)
WHERE max_input_tokens IS NULL
   OR max_output_tokens IS NULL;

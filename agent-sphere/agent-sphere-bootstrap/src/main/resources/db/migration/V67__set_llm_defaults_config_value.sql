-- ============================================================
-- V67：llm.defaults-config 默认值落库
-- temperature=0（最小有效值）、开思考、并行工具、usage 采集；不设 max_tokens。
-- 分离到独立版本：V66 已在部分库应用（空值），直接改 V66 会 checksum 失配。
-- ============================================================
UPDATE agent_system_config
SET config_value = '{"temperature":0,"thinking":true,"parallel_tool_calls":true,"include_usage":true}',
    updated_at   = NOW()
WHERE config_key = 'llm.defaults-config' AND config_group = 'llm';

-- 兜底：若 V66 行缺失（极端情况）则补齐
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
SELECT 'llm', 'llm.defaults-config',
       '{"temperature":0,"thinking":true,"parallel_tool_calls":true,"include_usage":true}',
       false,
       'LLM 采样默认配置（JSON，与实例/Completions config 同形状；实例级 config 字段级覆盖）'
WHERE NOT EXISTS (SELECT 1 FROM agent_system_config WHERE config_key = 'llm.defaults-config');
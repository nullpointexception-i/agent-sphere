-- ============================================================
-- V66：全局 LLM 采样默认配置（agent-runtime.llm 全局兜底）
-- 存于系统配置（与 instance/completions config 同 JSON 形状），留空=不设默认
-- ============================================================
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
VALUES ('llm', 'llm.defaults-config', '', false,
        'LLM 采样默认配置（JSON，与实例/Completions config 同形状；如 {"temperature":0.2}，留空不设默认）')
ON CONFLICT (config_key) DO NOTHING;
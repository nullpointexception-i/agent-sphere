-- ============================================================
-- V134: global Agent Router system prompt
-- ============================================================

INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
SELECT 'super-agent', 'super-agent.router-system-prompt', '', false,
       '全局 Agent Router 系统提示词；为空时使用代码内置默认模板'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_system_config WHERE config_key = 'super-agent.router-system-prompt'
);

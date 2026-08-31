-- V39: agent_identity_provider 增加资源模板（SSO 认证源初始化资源用）
-- 模板为资源描述符 JSON 数组，如 [{"type":"model_provider","name":"DeepSeek",...}]

ALTER TABLE agent_identity_provider ADD COLUMN IF NOT EXISTS resource_template JSONB;

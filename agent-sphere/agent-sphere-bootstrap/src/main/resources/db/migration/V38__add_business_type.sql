-- V38: instance / completions 增加业务域标识（business_type）
-- 供外部能力开放接口按 businessType 匹配资源；存量数据不回填（由管理端按需设置）

ALTER TABLE agent_instance ADD COLUMN IF NOT EXISTS business_type VARCHAR(64);
ALTER TABLE agent_completions ADD COLUMN IF NOT EXISTS business_type VARCHAR(64);

-- V33: completions / tasks 表补齐通用审计列（remark / updated_*）
-- 新增表时未含 remark；agent_completions_call 为追加型调用记录，补全 updated_* 保持通用字段规范

ALTER TABLE agent_completions ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE agent_completions_prompt ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE agent_completions_call ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE agent_completions_call ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);
ALTER TABLE agent_completions_call ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS remark VARCHAR(500);

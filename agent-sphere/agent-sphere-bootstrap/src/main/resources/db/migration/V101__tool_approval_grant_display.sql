ALTER TABLE agent_tool_approval_grant
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS security_level VARCHAR(8) NOT NULL DEFAULT 'L2';

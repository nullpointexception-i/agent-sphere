ALTER TABLE agent_user
    ADD COLUMN IF NOT EXISTS last_login_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_login_ip    VARCHAR(45),
    ADD COLUMN IF NOT EXISTS last_login_ua    VARCHAR(500);

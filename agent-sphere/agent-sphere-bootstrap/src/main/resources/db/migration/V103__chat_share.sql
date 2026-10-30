CREATE TABLE IF NOT EXISTS agent_chat_share (
    id BIGSERIAL PRIMARY KEY,
    share_token VARCHAR(64) NOT NULL,
    session_id BIGINT NOT NULL,
    anchor_run_id BIGINT NOT NULL,
    run_count INT NOT NULL DEFAULT 3,
    created_by VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    tenant_id BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    delete_flag SMALLINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_share_token
    ON agent_chat_share(share_token) WHERE delete_flag = 0;

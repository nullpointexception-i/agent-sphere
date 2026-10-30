CREATE TABLE IF NOT EXISTS agent_message_feedback (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    interaction_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    value VARCHAR(16) NOT NULL,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    delete_flag SMALLINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_message_feedback_user_interaction
    ON agent_message_feedback(user_id, interaction_id)
    WHERE delete_flag = 0;

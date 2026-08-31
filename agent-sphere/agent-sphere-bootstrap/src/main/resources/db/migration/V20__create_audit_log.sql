CREATE TABLE IF NOT EXISTS sys_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT,
    username      VARCHAR(100),
    action        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(64),
    resource_id   VARCHAR(128),
    detail        TEXT,
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(500),
    success       BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message TEXT,
    created_at    TIMESTAMP    DEFAULT NOW(),
    delete_flag   SMALLINT     NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_audit_log_user_id ON sys_audit_log(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action  ON sys_audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_log_created  ON sys_audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource ON sys_audit_log(resource_type, resource_id);

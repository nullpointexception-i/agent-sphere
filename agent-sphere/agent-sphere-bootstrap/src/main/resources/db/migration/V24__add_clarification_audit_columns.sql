-- Add standard audit columns to agent_pending_clarification
-- (DataPermissionInterceptor auto-appends created_by to every query)
ALTER TABLE agent_pending_clarification
    ADD COLUMN created_by   VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN updated_by   VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN updated_at   TIMESTAMP,
    ADD COLUMN delete_flag  SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN tenant_id    BIGINT      NOT NULL DEFAULT 0;

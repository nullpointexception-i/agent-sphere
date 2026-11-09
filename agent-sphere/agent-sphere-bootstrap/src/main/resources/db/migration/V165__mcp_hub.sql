-- ============================================================
-- V165: Tenant-scoped MCP Hub (publish / follow / install)
--
-- The hub stores a snapshot of a local MCP, including its auth_config.
-- Auth config is intentionally shared with the tenant and ancestor-visible
-- child tenants (the product decision for MCP Hub).
-- ============================================================

CREATE TABLE IF NOT EXISTS capability_mcp_hub (
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL,
    source_mcp_id     BIGINT,
    publisher_user_id BIGINT,
    name              VARCHAR(128) NOT NULL DEFAULT '',
    description       VARCHAR(512),
    server_url        VARCHAR(500) NOT NULL DEFAULT '',
    server_type       VARCHAR(64)  NOT NULL DEFAULT '',
    auth_config       TEXT,
    tool_definitions  TEXT,
    compress_enabled  BOOLEAN,
    access_mode       VARCHAR(20),
    security_level    VARCHAR(20),
    approval_scope    VARCHAR(20),
    version           INT          NOT NULL DEFAULT 1,
    source_version    INT,
    install_count     INT          NOT NULL DEFAULT 0,
    follow_count      INT          NOT NULL DEFAULT 0,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    delete_flag       SMALLINT     NOT NULL DEFAULT 0,
    created_by        VARCHAR(100),
    updated_by        VARCHAR(100),
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mcp_hub_source
    ON capability_mcp_hub (tenant_id, source_mcp_id)
    WHERE delete_flag = 0 AND source_mcp_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mcp_hub_tenant
    ON capability_mcp_hub (tenant_id, status);

CREATE TABLE IF NOT EXISTS capability_mcp_follow (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT    NOT NULL,
    user_id      BIGINT    NOT NULL,
    hub_mcp_id   BIGINT    NOT NULL,
    delete_flag  SMALLINT  NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mcp_follow
    ON capability_mcp_follow (user_id, hub_mcp_id) WHERE delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_mcp_follow_hub
    ON capability_mcp_follow (hub_mcp_id);

ALTER TABLE capability_mcp ADD COLUMN IF NOT EXISTS hub_mcp_id BIGINT;
ALTER TABLE capability_mcp ADD COLUMN IF NOT EXISTS auto_update BOOLEAN DEFAULT FALSE;
ALTER TABLE capability_mcp ADD COLUMN IF NOT EXISTS origin_version INT;
ALTER TABLE capability_mcp ADD COLUMN IF NOT EXISTS synced_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_mcp_hub_ref
    ON capability_mcp (hub_mcp_id);

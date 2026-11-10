-- ============================================================
-- V171: user/tenant resource ownership
-- ============================================================
-- USER: private to the creator (legacy behaviour).
-- TENANT: tenant-level resource, visible to tenant administrators.
ALTER TABLE agent_model_provider
    ADD COLUMN IF NOT EXISTS owner_scope VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE agent_model_route
    ADD COLUMN IF NOT EXISTS owner_scope VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE agent_instance
    ADD COLUMN IF NOT EXISTS owner_scope VARCHAR(16) NOT NULL DEFAULT 'USER';

-- Current GROUP_ROUTER instance of each tenant. Replaces the old system_managed
-- marker and the removed tenant group-router pointer.
ALTER TABLE sys_tenant
    ADD COLUMN IF NOT EXISTS current_instance_id BIGINT;

UPDATE agent_model_route
SET owner_scope = 'TENANT'
WHERE system_managed = true AND delete_flag = 0;

UPDATE agent_instance
SET owner_scope = 'TENANT'
WHERE system_managed = true AND delete_flag = 0;

UPDATE agent_model_provider p
SET owner_scope = 'TENANT'
WHERE p.id IN (
    SELECT r.provider_id FROM agent_model_route r WHERE r.owner_scope = 'TENANT'
);

UPDATE sys_tenant t
SET current_instance_id = (
    SELECT i.id
    FROM agent_instance i
    WHERE i.tenant_id = t.id
      AND i.instance_role = 'GROUP_ROUTER'
      AND i.owner_scope = 'TENANT'
      AND i.delete_flag = 0
    ORDER BY i.id DESC
    LIMIT 1
)
WHERE t.delete_flag = 0;

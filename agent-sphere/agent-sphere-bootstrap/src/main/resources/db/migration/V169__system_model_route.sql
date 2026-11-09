-- ============================================================
-- V169: system model routes and per-tenant group router instances
-- ============================================================
-- The route created together with a tenant becomes that tenant's system route.
-- The group router resolves it by type (system_managed) instead of a tenant FK.
ALTER TABLE agent_model_route
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill from the legacy tenant pointer before it is dropped.
UPDATE agent_model_route r
SET system_managed = true
FROM sys_tenant t
WHERE t.group_router_model_route_id = r.id
  AND t.delete_flag = 0;

-- One system route per tenant; agent_model_route.delete_flag is smallint.
CREATE UNIQUE INDEX IF NOT EXISTS uk_model_route_system_tenant
    ON agent_model_route (tenant_id)
    WHERE system_managed = true AND delete_flag = 0;

-- One GROUP_ROUTER instance per tenant; agent_instance.delete_flag is smallint.
CREATE UNIQUE INDEX IF NOT EXISTS uk_instance_group_router_tenant
    ON agent_instance (tenant_id)
    WHERE instance_role = 'GROUP_ROUTER' AND delete_flag = 0;

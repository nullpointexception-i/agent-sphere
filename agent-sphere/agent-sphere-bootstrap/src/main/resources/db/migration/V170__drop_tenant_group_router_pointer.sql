-- ============================================================
-- V170: drop the tenant group-router pointer
-- ============================================================
-- The tenant's system route and GROUP_ROUTER instance are now resolved by type.
-- Align existing instances with the legacy pointer, then remove the pointer.
UPDATE agent_instance i
SET model_route_id = t.group_router_model_route_id
FROM sys_tenant t
WHERE i.tenant_id = t.id
  AND i.instance_role = 'GROUP_ROUTER'
  AND i.delete_flag = 0
  AND t.group_router_model_route_id IS NOT NULL;

-- The startup-created shared instance (tenant 0) is superseded by the per-tenant
-- instances and has no route of its own.
UPDATE agent_instance
SET delete_flag = 1
WHERE instance_role = 'GROUP_ROUTER'
  AND tenant_id = 0
  AND delete_flag = 0;

ALTER TABLE sys_tenant
    DROP COLUMN IF EXISTS group_router_model_route_id;

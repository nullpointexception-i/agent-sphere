-- ============================================================
-- V166: group chat admins, auto public avatars, tenant group router
-- ============================================================

-- Distinguishes avatars auto-added from a member's public catalogue from
-- avatars an admin added manually. Removing a member removes only the former.
ALTER TABLE im_participant
    ADD COLUMN IF NOT EXISTS auto_added BOOLEAN NOT NULL DEFAULT FALSE;

-- Tenant-scoped model route used by the group chat router. Group creation and
-- router classification resolve this dynamically, so changing it affects all
-- groups of the tenant on the next batch.
ALTER TABLE sys_tenant
    ADD COLUMN IF NOT EXISTS group_router_model_route_id BIGINT;

-- One-time backfill from the legacy shared GROUP_ROUTER instance so existing
-- tenants keep working until an administrator configures their own route.
UPDATE sys_tenant t
SET group_router_model_route_id = (
    SELECT i.model_route_id
    FROM agent_instance i
    WHERE i.instance_role = 'GROUP_ROUTER'
      AND i.system_managed = TRUE
      AND i.model_route_id IS NOT NULL
      AND i.delete_flag = 0
    ORDER BY i.id
    LIMIT 1
)
WHERE t.group_router_model_route_id IS NULL;

-- If the legacy shared instance has no route, fall back to the tenant's first
-- existing model route so existing tenants are not blocked from creating groups.
UPDATE sys_tenant t
SET group_router_model_route_id = (
    SELECT r.id
    FROM agent_model_route r
    WHERE r.tenant_id = t.id
      AND r.delete_flag = 0
    ORDER BY r.id
    LIMIT 1
)
WHERE t.group_router_model_route_id IS NULL;

-- ============================================================
-- V192: one default model route per owner per tenant
--
-- The previous unique index only used created_by, so a user who
-- belonged to multiple tenants could not have a default route in
-- each tenant. Tenant resource provisioning then failed with a
-- duplicate-key error and aborted the whole resource transaction.
-- ============================================================

DROP INDEX IF EXISTS uk_route_default_owner;

CREATE UNIQUE INDEX uk_route_default_owner
    ON agent_model_route (tenant_id, (COALESCE(created_by, 'system')))
    WHERE is_default = TRUE AND delete_flag = 0;

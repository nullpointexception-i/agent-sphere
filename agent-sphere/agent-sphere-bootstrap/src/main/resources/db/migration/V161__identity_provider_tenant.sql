-- ============================================================
-- V161: Bind identity providers to an auto-provisioned tenant
--
-- A provider created from now on gets a tenant mounted under the platform
-- tenant. Legacy providers keep tenant_id NULL and continue to use the default
-- tenant fallback during SSO provisioning.
-- ============================================================

ALTER TABLE agent_identity_provider ADD COLUMN tenant_id BIGINT;
CREATE INDEX idx_identity_provider_tenant ON agent_identity_provider(tenant_id);

-- Group Router used to persist deliveries after GroupRouterRunService cleared the
-- caller's tenant context, so the audit fill stored tenant_id = 0. Those rows are
-- invisible to tenant-scoped delivery queries and never reach the local runtime.
-- Restore the owning event's tenant so normal dispatch can pick them up again.
UPDATE agent_event_delivery d
SET tenant_id = e.tenant_id
FROM agent_event e
WHERE d.event_id = e.id
  AND (d.tenant_id = 0 OR d.tenant_id IS NULL)
  AND e.tenant_id IS NOT NULL
  AND e.tenant_id <> 0;

-- ============================================================
-- V127: Super-Agent audit identity and tenant backfill
-- ============================================================

ALTER TABLE agent_thread
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor_id BIGINT;
ALTER TABLE agent_event
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor_id BIGINT;
ALTER TABLE agent_event_delivery
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor_id BIGINT;
ALTER TABLE agent_work_item
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor_id BIGINT;
ALTER TABLE agent_outbound_message
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor_id BIGINT;
ALTER TABLE im_message
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor_id BIGINT;

-- Tenant backfill from owner user.
UPDATE agent_super_agent a
SET tenant_id = u.tenant_id
FROM agent_user u
WHERE a.owner_user_id = u.id
  AND a.tenant_id IS NULL;

UPDATE im_conversation c
SET tenant_id = u.tenant_id
FROM agent_user u
WHERE c.owner_user_id = u.id
  AND c.tenant_id IS NULL;

UPDATE agent_thread t
SET tenant_id = c.tenant_id
FROM im_conversation c
WHERE c.thread_id = t.id
  AND t.tenant_id IS NULL;

UPDATE agent_event e
SET tenant_id = t.tenant_id
FROM agent_thread t
WHERE e.thread_id = t.id
  AND e.tenant_id IS NULL;

UPDATE agent_event_delivery d
SET tenant_id = e.tenant_id
FROM agent_event e
WHERE d.event_id = e.id
  AND d.tenant_id IS NULL;

UPDATE agent_work_item w
SET tenant_id = a.tenant_id
FROM agent_super_agent a
WHERE w.super_agent_id = a.id
  AND w.tenant_id IS NULL;

UPDATE agent_outbound_message o
SET tenant_id = a.tenant_id
FROM agent_super_agent a
WHERE o.super_agent_id = a.id
  AND o.tenant_id IS NULL;

UPDATE im_participant p
SET tenant_id = c.tenant_id
FROM im_conversation c
WHERE p.conversation_id = c.id
  AND p.tenant_id IS NULL;

UPDATE im_message m
SET tenant_id = c.tenant_id
FROM im_conversation c
WHERE m.conversation_id = c.id
  AND m.tenant_id IS NULL;

UPDATE im_read_cursor r
SET tenant_id = c.tenant_id
FROM im_conversation c
WHERE r.conversation_id = c.id
  AND r.tenant_id IS NULL;

-- created_by backfill for rows created by background workers.
UPDATE agent_work_item w
SET created_by = u.username
FROM agent_super_agent a
JOIN agent_user u ON u.id = a.owner_user_id
WHERE w.super_agent_id = a.id
  AND w.created_by = 'system';

UPDATE agent_outbound_message o
SET created_by = u.username
FROM agent_super_agent a
JOIN agent_user u ON u.id = a.owner_user_id
WHERE o.super_agent_id = a.id
  AND o.created_by = 'system';

UPDATE im_message m
SET created_by = u.username
FROM agent_super_agent a
JOIN agent_user u ON u.id = a.owner_user_id
WHERE m.sender_type = 'AGENT'
  AND m.sender_id = a.id
  AND m.created_by = 'system';

-- Actor backfill (best effort).
UPDATE agent_work_item SET actor_type = 'AGENT', actor_id = super_agent_id
WHERE actor_type IS NULL;

UPDATE agent_outbound_message SET actor_type = 'AGENT', actor_id = super_agent_id
WHERE actor_type IS NULL;

UPDATE im_message SET actor_type = sender_type, actor_id = sender_id
WHERE actor_type IS NULL;

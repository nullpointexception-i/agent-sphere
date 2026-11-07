-- ============================================================
-- V151: Tenant isolation columns and backfill
--
-- Adds tenant_id to tables that still lack it, then moves every legacy row
-- into the default tenant (id=2). Tables that are platform-wide or purely
-- operational are intentionally left shared.
-- ============================================================

ALTER TABLE agent_timeline
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

ALTER TABLE agent_task_artifact
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

ALTER TABLE agent_pending_clarification
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

ALTER TABLE sys_audit_log
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

-- Backfill legacy rows into the default tenant. Only 0/NULL rows are touched;
-- re-running this migration never overwrites a real tenant id.
DO $$
DECLARE
    t text;
    tables text[] := ARRAY[
        'agent_session',
        'agent_run',
        'agent_instance',
        'agent_instance_capability',
        'agent_memory',
        'agent_model_provider',
        'agent_api_key',
        'agent_model_route',
        'capability_mcp',
        'capability_skill',
        'capability_cli',
        'agent_tool_call_record',
        'agent_compact_record',
        'agent_user_in_loop_record',
        'agent_llm_interaction_record',
        'agent_pending_clarification',
        'agent_session_todo',
        'agent_completions',
        'agent_completions_prompt',
        'agent_completions_call',
        'agent_task',
        'agent_task_artifact',
        'agent_sub_agent_run',
        'agent_timeline',
        'agent_document',
        'agent_pending_wait',
        'agent_tool_approval',
        'agent_tool_approval_grant',
        'agent_message_feedback',
        'agent_chat_share',
        'agent_super_agent',
        'agent_channel_binding',
        'agent_thread',
        'agent_event',
        'agent_event_delivery',
        'agent_thread_subscription',
        'agent_work_item',
        'agent_outbound_message',
        'im_conversation',
        'im_participant',
        'im_message',
        'im_read_cursor',
        'im_conversation_setting',
        'agent_watchdog_run',
        'agent_session_input'
    ];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        EXECUTE format(
            'UPDATE public.%I SET tenant_id = 2 WHERE tenant_id IS NULL OR tenant_id = 0',
            t
        );
    END LOOP;
END $$;

-- Child rows inherit the tenant from their parent when they did not have it.
UPDATE agent_timeline t
SET tenant_id = s.tenant_id
FROM agent_session s
WHERE t.session_id = s.id
  AND (t.tenant_id IS NULL OR t.tenant_id = 0);

UPDATE agent_task_artifact a
SET tenant_id = t.tenant_id
FROM agent_task t
WHERE a.task_id = t.id
  AND (a.tenant_id IS NULL OR a.tenant_id = 0);

UPDATE agent_pending_clarification c
SET tenant_id = s.tenant_id
FROM agent_session s
WHERE c.session_id = s.id
  AND (c.tenant_id IS NULL OR c.tenant_id = 0);

UPDATE sys_audit_log
SET tenant_id = 2
WHERE tenant_id IS NULL OR tenant_id = 0;

-- Platform-managed system instances live in the platform tenant, not in the
-- default tenant. They are addressed by schedulers that run without a scope.
UPDATE agent_instance
SET tenant_id = 1
WHERE instance_role = 'GROUP_ROUTER'
   OR system_managed = TRUE;

CREATE INDEX IF NOT EXISTS idx_timeline_tenant
    ON agent_timeline (tenant_id);

CREATE INDEX IF NOT EXISTS idx_task_artifact_tenant
    ON agent_task_artifact (tenant_id);

CREATE INDEX IF NOT EXISTS idx_clarification_tenant
    ON agent_pending_clarification (tenant_id);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant
    ON sys_audit_log (tenant_id);

-- ============================================================
-- V163: Super-Agent public visibility for group chats
--
-- Owners can expose an avatar to other members of the same tenant when
-- they create a group chat. Private avatars stay owner-only.
-- ============================================================

ALTER TABLE agent_super_agent
    ADD COLUMN IF NOT EXISTS public_visible boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN agent_super_agent.public_visible
    IS 'Visible to same-tenant users when creating a group chat';

CREATE INDEX IF NOT EXISTS idx_agent_super_agent_public
    ON agent_super_agent (tenant_id, public_visible, status)
    WHERE delete_flag = false;

-- Approval inbox reads pending rows by owner; keep the hot path indexed.
CREATE INDEX IF NOT EXISTS idx_tool_approval_pending_session
    ON agent_tool_approval (status, session_id)
    WHERE delete_flag = 0;

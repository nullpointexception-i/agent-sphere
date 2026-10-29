-- Timeline audit row for L2 tool approvals (decision history is shown in chat).
ALTER TABLE agent_timeline
    ADD COLUMN IF NOT EXISTS ref_approval_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_timeline_ref_approval
    ON agent_timeline(ref_approval_id);

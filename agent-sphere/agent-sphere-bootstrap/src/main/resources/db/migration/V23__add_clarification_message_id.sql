ALTER TABLE agent_pending_clarification ADD COLUMN message_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_clarification_message ON agent_pending_clarification(message_id);

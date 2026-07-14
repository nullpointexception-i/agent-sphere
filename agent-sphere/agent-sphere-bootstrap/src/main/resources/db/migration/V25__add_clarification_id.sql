ALTER TABLE agent_pending_clarification ADD COLUMN clarification_id VARCHAR(16);
CREATE INDEX IF NOT EXISTS idx_clarification_id ON agent_pending_clarification(clarification_id);

-- ============================================================
-- V133: outbound send lease
-- ============================================================

ALTER TABLE agent_outbound_message
    ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_outbound_sending_lease
    ON agent_outbound_message (status, lease_until)
    WHERE delete_flag = FALSE;

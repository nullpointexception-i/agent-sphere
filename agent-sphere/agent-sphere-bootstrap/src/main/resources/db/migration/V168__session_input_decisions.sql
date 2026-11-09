-- ============================================================
-- V168: keep the decision of a resolved router input card
-- ============================================================
-- The resolved card must still show what was confirmed and which option the
-- owner picked, so the decision is stored with the input row and projected into
-- the card payload.
ALTER TABLE agent_session_input
    ADD COLUMN IF NOT EXISTS decisions_json TEXT;

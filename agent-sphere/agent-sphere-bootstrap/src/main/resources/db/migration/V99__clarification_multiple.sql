-- Clarification choice supports single or multiple selection.
ALTER TABLE agent_pending_clarification
    ADD COLUMN IF NOT EXISTS multiple BOOLEAN NOT NULL DEFAULT FALSE;

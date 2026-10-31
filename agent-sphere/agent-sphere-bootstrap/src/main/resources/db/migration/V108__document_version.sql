-- V108: display version for documents (incremented on every update).
ALTER TABLE agent_document
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

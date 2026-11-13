-- Requirement comment attachments (JSON array of attachment metadata).
ALTER TABLE pm_requirement_comment
    ADD COLUMN IF NOT EXISTS attachments TEXT;

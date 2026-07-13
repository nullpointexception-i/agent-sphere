-- Add encrypted flag for API key encryption (Phase 3)
ALTER TABLE agent_api_key ADD COLUMN IF NOT EXISTS encrypted VARCHAR(3) NOT NULL DEFAULT 'NO';

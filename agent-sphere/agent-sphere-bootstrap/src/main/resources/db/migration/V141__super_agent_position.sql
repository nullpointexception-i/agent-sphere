-- ============================================================
-- V141: super agent position (dictionary snapshot)
-- ============================================================

ALTER TABLE agent_super_agent
    ADD COLUMN IF NOT EXISTS position_code VARCHAR(64);
ALTER TABLE agent_super_agent
    ADD COLUMN IF NOT EXISTS position_name_cn VARCHAR(128);
ALTER TABLE agent_super_agent
    ADD COLUMN IF NOT EXISTS position_name_en VARCHAR(128);

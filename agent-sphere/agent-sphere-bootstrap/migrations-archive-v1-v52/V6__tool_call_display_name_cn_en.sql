-- ============================================================
-- agent_tool_call_record: rename display_name -> display_name_cn, add display_name_en
-- ============================================================
ALTER TABLE agent_tool_call_record
    RENAME COLUMN display_name TO display_name_cn;

ALTER TABLE agent_tool_call_record
    ADD COLUMN IF NOT EXISTS display_name_en VARCHAR(255) DEFAULT NULL;

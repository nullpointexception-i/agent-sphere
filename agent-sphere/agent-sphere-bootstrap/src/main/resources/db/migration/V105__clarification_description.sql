ALTER TABLE agent_pending_clarification
    ADD COLUMN IF NOT EXISTS description TEXT;

-- Backfill existing clarifications from the originating ask_clarification tool call.
UPDATE agent_pending_clarification c
SET description = src.description
FROM (
    SELECT DISTINCT ON (c2.id)
           c2.id,
           t.arguments_json::jsonb ->> 'description' AS description
    FROM agent_pending_clarification c2
    JOIN agent_tool_call_record t
      ON t.run_id = c2.run_id
     AND t.tool_name = 'builtin_7'
    WHERE c2.description IS NULL
      AND t.arguments_json IS NOT NULL
    ORDER BY c2.id, ABS(EXTRACT(EPOCH FROM (t.created_at - c2.created_at)))
) src
WHERE c.id = src.id
  AND src.description IS NOT NULL;

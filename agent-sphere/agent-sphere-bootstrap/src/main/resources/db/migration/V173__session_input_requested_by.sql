-- Track who triggered the parked Router input so strict clarification mode can
-- allow the asker (and the avatar owner / group admin) to answer.
ALTER TABLE agent_session_input
    ADD COLUMN IF NOT EXISTS requested_by BIGINT;

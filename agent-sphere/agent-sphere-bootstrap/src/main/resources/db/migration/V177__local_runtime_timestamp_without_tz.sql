-- Local runtime tables must use the same timestamp type as the rest of the
-- super-agent schema (TIMESTAMP without time zone), so MyBatis can map LocalDateTime.

ALTER TABLE agent_local_runtime
    ALTER COLUMN last_seen_at TYPE TIMESTAMP USING last_seen_at::timestamp,
    ALTER COLUMN created_at TYPE TIMESTAMP USING created_at::timestamp,
    ALTER COLUMN updated_at TYPE TIMESTAMP USING updated_at::timestamp;

ALTER TABLE agent_local_session
    ALTER COLUMN created_at TYPE TIMESTAMP USING created_at::timestamp,
    ALTER COLUMN updated_at TYPE TIMESTAMP USING updated_at::timestamp;

ALTER TABLE agent_local_task
    ALTER COLUMN lease_until TYPE TIMESTAMP USING lease_until::timestamp,
    ALTER COLUMN deadline_at TYPE TIMESTAMP USING deadline_at::timestamp,
    ALTER COLUMN started_at TYPE TIMESTAMP USING started_at::timestamp,
    ALTER COLUMN finished_at TYPE TIMESTAMP USING finished_at::timestamp,
    ALTER COLUMN created_at TYPE TIMESTAMP USING created_at::timestamp,
    ALTER COLUMN updated_at TYPE TIMESTAMP USING updated_at::timestamp;

ALTER TABLE agent_local_task_delivery
    ALTER COLUMN created_at TYPE TIMESTAMP USING created_at::timestamp;

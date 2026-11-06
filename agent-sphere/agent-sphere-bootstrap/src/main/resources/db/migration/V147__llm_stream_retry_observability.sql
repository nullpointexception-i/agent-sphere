-- ============================================================
-- V147: LLM transport retry observability
-- ============================================================
-- Provider-level connection/TLS/EOF retries happen inside the model client and were
-- only visible in logs. Persist them next to the interaction so latency outliers can
-- be diagnosed from data.

ALTER TABLE agent_llm_interaction_record
    ADD COLUMN IF NOT EXISTS stream_attempts INT,
    ADD COLUMN IF NOT EXISTS stream_retry_count INT,
    ADD COLUMN IF NOT EXISTS stream_error VARCHAR(512);

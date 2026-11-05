-- ============================================================
-- V130: Super-Agent watchdog runs
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_watchdog_run (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT,
    status           VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    dispatched_stuck INT NOT NULL DEFAULT 0,
    delivery_stuck   INT NOT NULL DEFAULT 0,
    outbound_stuck   INT NOT NULL DEFAULT 0,
    error_message    VARCHAR(1024),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    delete_flag      BOOLEAN NOT NULL DEFAULT FALSE,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_watchdog_started
    ON agent_watchdog_run (started_at DESC) WHERE delete_flag = FALSE;

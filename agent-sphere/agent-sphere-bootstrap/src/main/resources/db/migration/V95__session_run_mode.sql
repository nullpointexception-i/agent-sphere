-- Session preference + per-run snapshot.
ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS run_mode VARCHAR(16) NOT NULL DEFAULT 'WRITE';

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS run_mode VARCHAR(16) NOT NULL DEFAULT 'WRITE';

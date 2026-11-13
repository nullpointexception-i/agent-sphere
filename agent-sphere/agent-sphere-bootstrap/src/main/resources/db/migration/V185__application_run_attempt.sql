-- Track application-run retry attempts for timeout recovery.

ALTER TABLE biz_application_run
    ADD COLUMN IF NOT EXISTS attempt INT NOT NULL DEFAULT 0;

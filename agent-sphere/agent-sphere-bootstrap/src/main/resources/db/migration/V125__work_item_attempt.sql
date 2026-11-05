-- ============================================================
-- V125: Work Item 重试次数（Outbox 幂等键按 attempt 隔离）
-- ============================================================

ALTER TABLE agent_work_item
    ADD COLUMN IF NOT EXISTS attempt INT NOT NULL DEFAULT 1;

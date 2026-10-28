-- ============================================================
-- V93：当前 run 的 run-local 压缩
-- session 级记录保持 run_id IS NULL（HistoryLoader 的 session 游标只认这些）；
-- run-local 记录带 run_id + tail_json，用于单 run 长任务的压缩与 resume 恢复。
-- ============================================================

ALTER TABLE agent_compact_record ADD COLUMN IF NOT EXISTS run_id BIGINT;
ALTER TABLE agent_compact_record ADD COLUMN IF NOT EXISTS tail_json TEXT;

CREATE INDEX IF NOT EXISTS idx_compact_run_status ON agent_compact_record (run_id, status, id DESC);

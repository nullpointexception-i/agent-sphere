-- ============================================================
-- V92：统一 Timeline 支持「上下文压缩」行
-- COMPACTION 行的正文不落 timeline（表内只存轻量标签），
-- 刷新/翻页时按 ref_compact_id 从 agent_compact_record.summary_after 解析。
-- 只按主键回查压缩记录，不需要在 ref_compact_id 上建索引。
-- ============================================================

ALTER TABLE agent_timeline ADD COLUMN IF NOT EXISTS ref_compact_id BIGINT;

-- ============================================================
-- V68：skill hub 发布/安装基础字段
-- visibility: PRIVATE=仅作者可见（默认）, PUBLIC=hub 公开
-- origin_skill_id: 安装副本指回源 skill（fork 血缘）
-- install_count: 源 skill 被复制次数
-- ============================================================
ALTER TABLE capability_skill
    ADD COLUMN IF NOT EXISTS visibility    VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    ADD COLUMN IF NOT EXISTS origin_skill_id BIGINT,
    ADD COLUMN IF NOT EXISTS install_count  INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_skill_visibility ON capability_skill (visibility, delete_flag);
CREATE INDEX IF NOT EXISTS idx_skill_origin ON capability_skill (origin_skill_id);

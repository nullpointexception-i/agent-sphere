-- ============================================================
-- V70：skill 版本追踪与自动更新
-- version:        源技能内容版本，每次内容编辑（updateSkill）自增；创建时为 1
-- origin_version: 已安装副本记录「上次同步时源技能的版本」，仅副本有意义
-- auto_update:    已安装副本是否随源版本自动更新（仅副本有意义）
-- ============================================================
ALTER TABLE capability_skill
    ADD COLUMN IF NOT EXISTS version        INT       NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS origin_version INT       NULL,
    ADD COLUMN IF NOT EXISTS auto_update    BOOLEAN   NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_skill_auto_update ON capability_skill (auto_update, origin_skill_id);
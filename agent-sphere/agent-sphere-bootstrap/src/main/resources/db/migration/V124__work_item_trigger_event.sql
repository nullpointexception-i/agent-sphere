-- ============================================================
-- V124: Work Item 关联触发事件（幂等派发）
-- ============================================================

ALTER TABLE agent_work_item
    ADD COLUMN IF NOT EXISTS trigger_event_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_work_item_trigger_event
    ON agent_work_item (trigger_event_id, super_agent_id)
    WHERE delete_flag = FALSE AND trigger_event_id IS NOT NULL;

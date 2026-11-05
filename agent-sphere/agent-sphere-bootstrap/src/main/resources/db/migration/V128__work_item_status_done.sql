-- ============================================================
-- V128: Normalize terminal work item status
-- ============================================================

UPDATE agent_work_item
SET status = 'DONE'
WHERE status = 'COMPLETED' AND delete_flag = FALSE;

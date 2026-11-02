-- ============================================================
-- V112：LLM 轮次尝试链（attempt_no）
--
-- 背景：同一 (run_id, turn_no) 可能经历多次路由尝试（主路由失败 → fallback）。
-- 旧唯一键只允许一行，fallback 会覆盖主路由的失败信息，导致无法定位真实原因。
--
-- 本迁移把尝试序号纳入唯一键：每个 attempt 独立一行。
-- ============================================================

ALTER TABLE agent_llm_interaction_record
    ADD COLUMN IF NOT EXISTS attempt_no INT NOT NULL DEFAULT 0;

DROP INDEX IF EXISTS uk_llm_interaction_run_turn;
DROP INDEX IF EXISTS idx_llm_interaction_run_turn;

CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_interaction_run_turn
    ON agent_llm_interaction_record (run_id, turn_no, attempt_no)
    WHERE interaction_type = 'CHAT_REPLY'
      AND sub_agent_run_id IS NULL
      AND delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_llm_interaction_run_turn
    ON agent_llm_interaction_record (run_id, turn_no, attempt_no)
    WHERE delete_flag = 0;

-- ============================================================
-- V117：每次 LLM 调用独立记录（重试不再覆盖成功结果）
--
-- 背景：preCreateTurnRecord 每次重试都用同一个 (run_id, turn_no, attempt_no) 复用记录，
-- 后一次失败会把前一次成功的 reply_content/reasoning 覆盖成空，时间线回复随之消失；
-- 内联重试更是完全不可见。retry_no 让每次调用独立一行，retry_attempt 标记退避档位。
--
-- attempt_no 语义不变（0=主路由，1..N=fallback），retry_no 是该组合下的调用序号。
-- ============================================================

ALTER TABLE agent_llm_interaction_record ADD COLUMN IF NOT EXISTS retry_no INT NOT NULL DEFAULT 0;
ALTER TABLE agent_llm_interaction_record ADD COLUMN IF NOT EXISTS retry_attempt INT;

DROP INDEX IF EXISTS uk_llm_interaction_run_turn;

CREATE UNIQUE INDEX uk_llm_interaction_run_turn
    ON agent_llm_interaction_record (run_id, turn_no, attempt_no, retry_no)
    WHERE interaction_type = 'CHAT_REPLY'
      AND sub_agent_run_id IS NULL
      AND delete_flag = 0;

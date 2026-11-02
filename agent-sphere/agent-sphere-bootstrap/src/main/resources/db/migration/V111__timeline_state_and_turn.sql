-- ============================================================
-- V111：LLM 轮次身份 + Timeline 状态词表归一
--
-- 原则：
--   - 执行状态归执行表（agent_run / agent_llm_interaction_record / 工具 / 澄清 / 审批）；
--   - agent_timeline 只保留展示快照，读取时由来源表覆盖；
--   - 状态词表统一 UPPER_SNAKE，不再接受 started / succeeded 等历史写法。
--
-- 本迁移一次性收敛历史数据，不保留兼容形态。
-- ============================================================

-- ---------- 1. 执行侧：每轮 LLM 一条记录 ----------
-- 表约 11GB：绝不能全表 UPDATE。加列用「常量默认值」（PG11+ 只改元数据，不重写表），
-- 只回填失败行（success=false，数量很小）。
ALTER TABLE agent_llm_interaction_record ADD COLUMN IF NOT EXISTS turn_no INT;
ALTER TABLE agent_llm_interaction_record
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';

UPDATE agent_llm_interaction_record
SET status = 'FAILED'
WHERE success IS NOT TRUE AND status <> 'FAILED';

-- 同一 (run, turn) 只允许一条主 Agent 问答轮记录；重启/重试复用同一行
CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_interaction_run_turn
    ON agent_llm_interaction_record (run_id, turn_no)
    WHERE interaction_type = 'CHAT_REPLY'
      AND sub_agent_run_id IS NULL
      AND delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_llm_interaction_run_turn
    ON agent_llm_interaction_record (run_id, turn_no)
    WHERE delete_flag = 0;

-- ---------- 2. 视图侧：assistant 行按 interaction 去重 ----------
WITH ranked AS (
    SELECT id, row_number() OVER (
               PARTITION BY run_id, ref_interaction_id ORDER BY seq
           ) AS rn
    FROM agent_timeline
    WHERE kind = 'assistant'
      AND ref_interaction_id IS NOT NULL
      AND delete_flag = 0
)
UPDATE agent_timeline t
SET delete_flag = 1, updated_at = now()
FROM ranked r
WHERE t.id = r.id AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_timeline_assistant_ref
    ON agent_timeline (run_id, ref_interaction_id)
    WHERE kind = 'assistant'
      AND ref_interaction_id IS NOT NULL
      AND delete_flag = 0;

-- ---------- 3. 状态词表归一 ----------
UPDATE agent_timeline
SET state = CASE lower(state)
    WHEN 'started'     THEN 'IN_PROGRESS'
    WHEN 'in_progress' THEN 'IN_PROGRESS'
    WHEN 'succeeded'   THEN 'SUCCEEDED'
    WHEN 'failed'      THEN 'FAILED'
    ELSE upper(state)
END
WHERE state IS NOT NULL AND state <> upper(state);

UPDATE agent_timeline
SET subtype = NULL
WHERE subtype IS NOT NULL AND lower(subtype) = lower(state);

-- ---------- 4. 历史孤儿 assistant 行直接终结 ----------
UPDATE agent_timeline t
SET state = 'CANCELLED', subtype = 'CANCELLED', updated_at = now()
WHERE t.kind = 'assistant'
  AND t.state = 'RUNNING'
  AND t.delete_flag = 0
  AND EXISTS (
      SELECT 1 FROM agent_run r
      WHERE r.id = t.run_id
        AND r.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
  );

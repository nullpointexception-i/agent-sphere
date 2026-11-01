-- ============================================================
-- V109：Timeline 幂等收敛 + 唯一索引
--
-- 背景：TimelineRecorder 的进程内 Map 只在单副本有效。线上 2 副本无粘性，
-- PENDING/RESPONDED、RUNNING/COMPLETED 可能落在不同副本（或进程重启），
-- 导致同一澄清/工具/run_status 被插入两行；前端的 hasPendingClarifications
-- 看到旧 PENDING 行后会永久锁住输入框（线上 session 1087 即此问题）。
--
-- 本迁移：
--   1) 按 ref 收敛重复行：保留信息最完整的一行，逻辑删除其余行；
--   2) 用 agent_run 的终态修正 run_status 行；
--   3) 加部分唯一索引，作为代码兜底，防止再次产生重复行。
--
-- 注意：delete_flag 是 SMALLINT（0/1），不是 boolean。
-- ============================================================

-- ---------- 1. 澄清：同一 ref_clarification_id 只保留一行 ----------
WITH ranked AS (
    SELECT t.id,
           t.seq,
           c.user_response,
           row_number() OVER (
               PARTITION BY t.ref_clarification_id
               ORDER BY CASE WHEN t.state = 'PENDING' THEN 0 ELSE 1 END, t.seq
           ) AS rn
    FROM agent_timeline t
    JOIN agent_pending_clarification c ON c.id = t.ref_clarification_id
    WHERE t.kind = 'clarification'
      AND t.delete_flag = 0
      AND t.ref_clarification_id IS NOT NULL
),
keep AS (
    SELECT id, user_response FROM ranked WHERE rn = 1
)
UPDATE agent_timeline t
SET state = CASE
        WHEN k.user_response IS NULL THEN 'PENDING'
        WHEN k.user_response LIKE '%__dismissed__%' THEN 'CANCELLED'
        ELSE 'ANSWERED'
    END,
    subtype = CASE
        WHEN k.user_response IS NULL THEN 'PENDING'
        WHEN k.user_response LIKE '%__dismissed__%' THEN 'CANCELLED'
        ELSE 'ANSWERED'
    END,
    updated_at = now()
FROM keep k
WHERE t.id = k.id;

WITH ranked AS (
    SELECT t.id,
           row_number() OVER (
               PARTITION BY t.ref_clarification_id
               ORDER BY CASE WHEN t.state = 'PENDING' THEN 0 ELSE 1 END, t.seq
           ) AS rn
    FROM agent_timeline t
    WHERE t.kind = 'clarification'
      AND t.delete_flag = 0
      AND t.ref_clarification_id IS NOT NULL
)
UPDATE agent_timeline t
SET delete_flag = 1, updated_at = now()
FROM ranked r
WHERE t.id = r.id AND r.rn > 1;

-- ---------- 2. run_status：每个 run 只保留一行 ----------
WITH ranked AS (
    SELECT t.id,
           row_number() OVER (
               PARTITION BY t.run_id
               ORDER BY CASE WHEN t.state IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN 0 ELSE 1 END,
                        t.seq DESC
           ) AS rn
    FROM agent_timeline t
    WHERE t.kind = 'run_status'
      AND t.delete_flag = 0
      AND t.run_id IS NOT NULL
)
UPDATE agent_timeline t
SET delete_flag = 1, updated_at = now()
FROM ranked r
WHERE t.id = r.id AND r.rn > 1;

-- 用 agent_run 的终态修正保留行的状态/标题
UPDATE agent_timeline t
SET state = r.status,
    subtype = r.status,
    title = CASE r.status
        WHEN 'COMPLETED' THEN to_char(coalesce(r.updated_at, now()), 'YYYY-MM-DD HH24:MI:SS')
        WHEN 'FAILED' THEN '❌ 执行失败'
        WHEN 'CANCELLED' THEN '⏹️ 已取消'
        ELSE t.title
    END,
    updated_at = now()
FROM agent_run r
WHERE t.run_id = r.id
  AND t.kind = 'run_status'
  AND t.delete_flag = 0
  AND r.status IN ('COMPLETED', 'FAILED', 'CANCELLED');

-- ---------- 3. 工具行：同一 run + ref_tool_call_id 只保留一行 ----------
WITH ranked AS (
    SELECT t.id,
           row_number() OVER (
               PARTITION BY t.run_id, t.ref_tool_call_id
               ORDER BY t.seq
           ) AS rn
    FROM agent_timeline t
    WHERE t.kind = 'tool'
      AND t.delete_flag = 0
      AND t.ref_tool_call_id IS NOT NULL
),
keep AS (
    SELECT id FROM ranked WHERE rn = 1
)
UPDATE agent_timeline t
SET state = CASE upper(coalesce(c.status, ''))
        WHEN 'SUCCEEDED' THEN 'succeeded'
        WHEN 'FAILED' THEN 'failed'
        ELSE 'in_progress'
    END,
    subtype = CASE upper(coalesce(c.status, ''))
        WHEN 'SUCCEEDED' THEN 'succeeded'
        WHEN 'FAILED' THEN 'failed'
        ELSE 'in_progress'
    END,
    updated_at = now()
FROM agent_tool_call_record c
WHERE t.id IN (SELECT id FROM keep)
  AND c.id = t.ref_tool_call_id;

WITH ranked AS (
    SELECT t.id,
           row_number() OVER (
               PARTITION BY t.run_id, t.ref_tool_call_id
               ORDER BY t.seq
           ) AS rn
    FROM agent_timeline t
    WHERE t.kind = 'tool'
      AND t.delete_flag = 0
      AND t.ref_tool_call_id IS NOT NULL
)
UPDATE agent_timeline t
SET delete_flag = 1, updated_at = now()
FROM ranked r
WHERE t.id = r.id AND r.rn > 1;

-- ---------- 4. 工具审批行：同一 run + ref_approval_id 只保留一行 ----------
WITH ranked AS (
    SELECT t.id,
           a.status AS approval_status,
           row_number() OVER (
               PARTITION BY t.run_id, t.ref_approval_id
               ORDER BY t.seq
           ) AS rn
    FROM agent_timeline t
    JOIN agent_tool_approval a ON a.id = t.ref_approval_id
    WHERE t.kind = 'tool_approval'
      AND t.delete_flag = 0
      AND t.ref_approval_id IS NOT NULL
),
keep AS (
    SELECT id, approval_status FROM ranked WHERE rn = 1
)
UPDATE agent_timeline t
SET state = CASE
        WHEN k.approval_status = 'PENDING' THEN 'PENDING'
        WHEN k.approval_status = 'DISMISSED' THEN 'CANCELLED'
        ELSE 'ANSWERED'
    END,
    subtype = CASE
        WHEN k.approval_status = 'PENDING' THEN 'PENDING'
        WHEN k.approval_status = 'DISMISSED' THEN 'CANCELLED'
        ELSE 'ANSWERED'
    END,
    title = CASE
        WHEN k.approval_status = 'PENDING' THEN '等待工具授权'
        WHEN k.approval_status = 'DISMISSED' THEN '已取消工具授权'
        ELSE '已处理工具授权'
    END,
    updated_at = now()
FROM keep k
WHERE t.id = k.id;

WITH ranked AS (
    SELECT t.id,
           row_number() OVER (
               PARTITION BY t.run_id, t.ref_approval_id
               ORDER BY t.seq
           ) AS rn
    FROM agent_timeline t
    WHERE t.kind = 'tool_approval'
      AND t.delete_flag = 0
      AND t.ref_approval_id IS NOT NULL
)
UPDATE agent_timeline t
SET delete_flag = 1, updated_at = now()
FROM ranked r
WHERE t.id = r.id AND r.rn > 1;

-- ---------- 5. 唯一索引：代码兜底 ----------
CREATE UNIQUE INDEX IF NOT EXISTS uk_timeline_clarification_ref
    ON agent_timeline (run_id, ref_clarification_id)
    WHERE kind = 'clarification'
      AND ref_clarification_id IS NOT NULL
      AND delete_flag = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_timeline_run_status
    ON agent_timeline (run_id)
    WHERE kind = 'run_status'
      AND run_id IS NOT NULL
      AND delete_flag = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_timeline_tool_ref
    ON agent_timeline (run_id, ref_tool_call_id)
    WHERE kind = 'tool'
      AND ref_tool_call_id IS NOT NULL
      AND delete_flag = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_timeline_approval_ref
    ON agent_timeline (run_id, ref_approval_id)
    WHERE kind = 'tool_approval'
      AND ref_approval_id IS NOT NULL
      AND delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_timeline_run_kind
    ON agent_timeline (run_id, kind)
    WHERE delete_flag = 0;

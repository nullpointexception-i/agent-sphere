-- ============================================================
-- V56：统一 Timeline 打平展示索引
-- 会话级单调 seq（Redis INCR 分配）为排序/分页/SSE 唯一契约；
-- 表内仅存轻量标签与 ref，正文在加载时从原表解析（不落 payload）。
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_timeline (
    id                   BIGSERIAL PRIMARY KEY,
    session_id           BIGINT       NOT NULL,
    run_id               BIGINT,
    seq                  BIGINT       NOT NULL,
    kind                 VARCHAR(30)  NOT NULL,   -- user|assistant|tool|clarification|subagent|run_status|error
    subtype              VARCHAR(40),
    state                VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    group_id             BIGINT,
    title                VARCHAR(300),            -- 展示快照标签（无正文）
    ref_run_id           BIGINT,
    ref_interaction_id   BIGINT,
    ref_tool_call_id     BIGINT,
    ref_sub_agent_run_id BIGINT,
    ref_clarification_id BIGINT,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark               VARCHAR(500),
    delete_flag          SMALLINT     NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_timeline_session_seq ON agent_timeline (session_id, seq);
CREATE INDEX IF NOT EXISTS idx_timeline_group  ON agent_timeline (session_id, group_id);
CREATE INDEX IF NOT EXISTS idx_timeline_subrun ON agent_timeline (ref_sub_agent_run_id);
CREATE INDEX IF NOT EXISTS idx_timeline_run     ON agent_timeline (run_id);
-- ============================================================
-- V55：LLM interaction / tool_call 增加 reasoning 与子 Agent 归属列；
-- 新建通用子 Agent 运行表 agent_sub_agent_run（复用 interactions / tool_calls）
-- ============================================================

-- 1) LLM interaction：reasoning 分列（与 reply 拆分）+ 子 Agent 归属
ALTER TABLE agent_llm_interaction_record
    ADD COLUMN IF NOT EXISTS reasoning        TEXT,
    ADD COLUMN IF NOT EXISTS reply_content    TEXT,
    ADD COLUMN IF NOT EXISTS sub_agent_run_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_llm_interaction_subrun
    ON agent_llm_interaction_record (sub_agent_run_id);

-- 2) tool_call：子 Agent 归属
ALTER TABLE agent_tool_call_record
    ADD COLUMN IF NOT EXISTS sub_agent_run_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_toolcall_subrun
    ON agent_tool_call_record (sub_agent_run_id);

-- 3) 通用子 Agent 运行表
CREATE TABLE IF NOT EXISTS agent_sub_agent_run (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT NOT NULL,
    run_id              BIGINT,
    parent_run_id       BIGINT,
    parent_tool_call_id VARCHAR(100),
    agent_type          VARCHAR(30)  NOT NULL DEFAULT 'SKILL',
    agent_ref           VARCHAR(100) NOT NULL DEFAULT '',
    display_name        VARCHAR(200) NOT NULL DEFAULT '',
    status              VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    started_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    finished_at         TIMESTAMP,
    delete_flag         SMALLINT NOT NULL DEFAULT 0,
    tenant_id           BIGINT NOT NULL DEFAULT 0,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_subrun_session ON agent_sub_agent_run (session_id, delete_flag);
CREATE INDEX IF NOT EXISTS idx_subrun_run ON agent_sub_agent_run (run_id, delete_flag);
CREATE INDEX IF NOT EXISTS idx_subrun_parent_tool ON agent_sub_agent_run (parent_tool_call_id);
DROP TRIGGER IF EXISTS trg_sub_run_updated_at ON agent_sub_agent_run;
CREATE TRIGGER trg_sub_run_updated_at BEFORE UPDATE ON agent_sub_agent_run
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
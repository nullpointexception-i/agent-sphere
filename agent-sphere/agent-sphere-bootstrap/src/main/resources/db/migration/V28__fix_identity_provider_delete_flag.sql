-- V27 将 agent_identity_provider.delete_flag 建成 BOOLEAN，
-- 与全仓库 SMALLINT 约定不一致；MyBatis-Plus 逻辑删除注入 `WHERE delete_flag=0`（整数），
-- Postgres 对 BOOLEAN 列报 `operator does not exist: boolean = integer`。
-- 本迁移将其统一为 SMALLINT NOT NULL DEFAULT 0（对齐 V1 等既有表）。

ALTER TABLE agent_identity_provider ALTER COLUMN delete_flag DROP DEFAULT;
ALTER TABLE agent_identity_provider ALTER COLUMN delete_flag TYPE SMALLINT USING (CASE WHEN delete_flag THEN 1 ELSE 0 END);
ALTER TABLE agent_identity_provider ALTER COLUMN delete_flag SET DEFAULT 0;

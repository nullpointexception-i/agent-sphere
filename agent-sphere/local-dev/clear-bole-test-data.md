# 清理 bole SSO 测试用户及其私有资源

## 登录数据库（psql）

```bash
# 方式一：Postgres 跑在 docker-compose 中（容器名以实际 docker ps 为准）
docker exec -it agent-docker-middleware-postgres-1 psql -U buukle -d buukle_agent_2026061101

# 方式二：本机有 psql 客户端（默认连接参数）
PGPASSWORD=buukle123 psql -h 127.0.0.1 -p 5432 -U buukle -d buukle_agent_2026061101

# 方式三：后端 docker-compose 未启动时先启动中间件
# docker compose -f agent-docker-middleware/docker-compose.yml up -d
```

> 连接参数默认值：`DB_HOST=127.0.0.1`、`DB_PORT=5432`、`DB_USERNAME=buukle`、`DB_PASSWORD=buukle123`、库名 `buukle_agent_2026061101`；如后端用环境变量覆盖过，按实际值连接。

## 清理脚本（自包含，无需临时表，可重复执行）

直接整段粘贴执行（**不要**包在 `BEGIN…COMMIT` 里；逐条自动提交，单表失败不阻断其余）。

用户识别 = **SSO 关联**（`agent_sso_identity.provider_code='bole'`）**或 用户名前缀 `bole_`**（SSO 身份行可能已被删，用户名由 `bole_<subject>` 派生，以此兜底）。

```sql
-- 0) 预览本次会被清理的用户（先看再删）
SELECT id, username FROM agent_user
WHERE username LIKE 'bole\_%'
   OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole');

-- 1) 任务产物 / 任务
DELETE FROM agent_task_artifact
WHERE task_id IN (SELECT id FROM agent_task
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_task
WHERE created_by IN (SELECT username FROM agent_user
  WHERE username LIKE 'bole\_%'
     OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));

-- 2) completions 调用/版本 + completions
DELETE FROM agent_completions_call
WHERE completions_id IN (SELECT id FROM agent_completions
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_completions_prompt
WHERE completions_id IN (SELECT id FROM agent_completions
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_completions
WHERE created_by IN (SELECT username FROM agent_user
  WHERE username LIKE 'bole\_%'
     OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));

-- 3) 会话子表（后台线程可能落 system，按父 session/run/task 归属）
DELETE FROM agent_session_todo
WHERE session_id IN (SELECT id FROM agent_session
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_compact_record
WHERE session_id IN (SELECT id FROM agent_session
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_tool_call_record
WHERE session_id IN (SELECT id FROM agent_session
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')))
   OR run_id IN (SELECT id FROM agent_run WHERE session_id IN (SELECT id FROM agent_session
    WHERE created_by IN (SELECT username FROM agent_user
      WHERE username LIKE 'bole\_%'
         OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'))));

DELETE FROM agent_llm_interaction_record
WHERE session_id IN (SELECT id FROM agent_session
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')))
   OR run_id IN (SELECT id FROM agent_run WHERE session_id IN (SELECT id FROM agent_session
    WHERE created_by IN (SELECT username FROM agent_user
      WHERE username LIKE 'bole\_%'
         OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'))));

DELETE FROM agent_user_in_loop_record
WHERE session_id IN (SELECT id FROM agent_session
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')))
   OR run_id IN (SELECT id FROM agent_run WHERE session_id IN (SELECT id FROM agent_session
    WHERE created_by IN (SELECT username FROM agent_user
      WHERE username LIKE 'bole\_%'
         OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'))));

DELETE FROM agent_memory
WHERE session_id IN (SELECT id FROM agent_session
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')))
   OR run_id IN (SELECT id FROM agent_run WHERE session_id IN (SELECT id FROM agent_session
    WHERE created_by IN (SELECT username FROM agent_user
      WHERE username LIKE 'bole\_%'
         OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'))))
   OR task_id IN (SELECT id FROM agent_task
    WHERE created_by IN (SELECT username FROM agent_user
      WHERE username LIKE 'bole\_%'
         OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

-- 4) run / session
DELETE FROM agent_run
WHERE session_id IN (SELECT id FROM agent_session
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_session
WHERE created_by IN (SELECT username FROM agent_user
  WHERE username LIKE 'bole\_%'
     OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));

-- 5) 实例能力 / 实例
DELETE FROM agent_instance_capability
WHERE instance_id IN (SELECT id FROM agent_instance
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_instance
WHERE created_by IN (SELECT username FROM agent_user
  WHERE username LIKE 'bole\_%'
     OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));

-- 6) 路由 / API Key / 供应商
DELETE FROM agent_model_route
WHERE provider_id IN (SELECT id FROM agent_model_provider
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_api_key
WHERE provider_id IN (SELECT id FROM agent_model_provider
  WHERE created_by IN (SELECT username FROM agent_user
    WHERE username LIKE 'bole\_%'
       OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole')));

DELETE FROM agent_model_provider
WHERE created_by IN (SELECT username FROM agent_user
  WHERE username LIKE 'bole\_%'
     OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));

-- 7) MCP / 技能 / CLI / 文档
DELETE FROM capability_mcp   WHERE created_by IN (SELECT username FROM agent_user WHERE username LIKE 'bole\_%' OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));
DELETE FROM capability_skill WHERE created_by IN (SELECT username FROM agent_user WHERE username LIKE 'bole\_%' OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));
DELETE FROM capability_cli   WHERE created_by IN (SELECT username FROM agent_user WHERE username LIKE 'bole\_%' OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));
DELETE FROM agent_document   WHERE created_by IN (SELECT username FROM agent_user WHERE username LIKE 'bole\_%' OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));

-- 8) 角色绑定 / 审计 / SSO 身份 / 用户本体
DELETE FROM sys_user_role
WHERE user_id IN (SELECT id FROM agent_user
  WHERE username LIKE 'bole\_%'
     OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));

DELETE FROM sys_audit_log
WHERE user_id IN (SELECT id FROM agent_user
  WHERE username LIKE 'bole\_%'
     OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole'));  -- 可选

DELETE FROM agent_sso_identity WHERE provider_code = 'bole';

DELETE FROM agent_user
WHERE username LIKE 'bole\_%'
   OR id IN (SELECT agent_user_id FROM agent_sso_identity WHERE provider_code = 'bole');

-- 9) 校验：应返回 0 行
SELECT count(*) AS leftover_bole_users FROM agent_user WHERE username LIKE 'bole\_%';
```

## 说明与排错
- **不要**用 `BEGIN…COMMIT` 包裹：任一语句报错会把整个事务置为 aborted，后续全部失效（含 `COMMIT` 变成 `ROLLBACK`）。
- **自包含、无临时表**：每张表用内联子查询识别 bole 用户，避免「临时表随会话/粘贴片段丢失」导致用户删除漏跑。
- **识别方式**：SSO 关联 或 用户名前缀 `bole_`（SSO 身份行可能已被删，`bole_<subject>` 用户名作兜底）。
- 子表按父资源归属删除（后台线程写入的 `agent_task_artifact` 等 `created_by` 为 `system`）。
- 若某表在当前库不存在（`relation "xxx" does not exist`），跳过该条即可。
- `agent_identity_provider`（bole 身份源配置）保留，下次 SSO 登录会重新开通用户。

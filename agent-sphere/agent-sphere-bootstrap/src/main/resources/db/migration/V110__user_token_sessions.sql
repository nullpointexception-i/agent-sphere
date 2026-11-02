-- ============================================================
-- V110：多端登录会话（替代 agent_user.token 单列）
--
-- 背景：agent_user.token 是单列，登录即覆盖。主站与 widget 各自发起一次登录，
-- 后登录的一方会把先登录方的 token 覆盖掉，于是双方互相踢下线。
--
-- 本迁移：
--   1) 新增 agent_user_token，一个用户可同时持有多个有效 token；
--   2) 把现有 token 以 SHA-256 回填到新表（发布后现有会话不失效）；
--   3) 删除 agent_user.token 旧列。
--
-- 注意：token 只存 hash（小写 hex），明文不落库；token_prefix 仅供排查。
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_user_token (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    token_prefix VARCHAR(12)  NOT NULL,
    source       VARCHAR(16)  NOT NULL,
    user_agent   VARCHAR(500),
    login_ip     VARCHAR(64),
    expires_at   TIMESTAMP    NOT NULL,
    last_used_at TIMESTAMP,
    revoked_at   TIMESTAMP,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark       VARCHAR(500),
    delete_flag  SMALLINT     NOT NULL DEFAULT 0,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_token_hash
    ON agent_user_token (token_hash) WHERE delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_user_token_user
    ON agent_user_token (user_id, status, last_used_at DESC)
    WHERE delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_user_token_expiry
    ON agent_user_token (expires_at)
    WHERE delete_flag = 0;

-- 回填现有会话：必须在 DROP COLUMN 之前执行。
-- sha256(bytea) 是 PostgreSQL 11+ 内置函数，不需要 pgcrypto。
INSERT INTO agent_user_token
    (user_id, token_hash, token_prefix, source, expires_at, status)
SELECT id,
       encode(sha256(convert_to(token, 'UTF8')), 'hex'),
       left(token, 8),
       'LEGACY',
       now() + interval '30 days',
       'ACTIVE'
FROM agent_user
WHERE token IS NOT NULL AND token <> ''
ON CONFLICT DO NOTHING;

ALTER TABLE agent_user DROP COLUMN IF EXISTS token;

-- 通用文件存储表：基于数据库的二进制存储（biz_key + file_key 唯一），
-- 供插件安装包等场景复用；PG bytea 二进制列，多副本共享库天然一致。
CREATE TABLE agent_file_store (
    id           BIGSERIAL PRIMARY KEY,
    biz_key      VARCHAR(64)  NOT NULL,
    file_key     VARCHAR(128) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content      BYTEA        NOT NULL,
    size_bytes   BIGINT       NOT NULL DEFAULT 0,
    content_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark       VARCHAR(500),
    delete_flag  SMALLINT     NOT NULL DEFAULT 0,
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_file_biz UNIQUE (biz_key, file_key)
);

CREATE INDEX idx_file_store_biz ON agent_file_store (biz_key, file_key);
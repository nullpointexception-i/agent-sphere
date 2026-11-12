-- Wiki document folders and page comments.

ALTER TABLE agent_document ADD COLUMN IF NOT EXISTS folder_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_doc_folder
    ON agent_document (tenant_id, created_by, folder_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_document_folder (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL DEFAULT 0,
    owner_user_id BIGINT       NOT NULL,
    parent_id     BIGINT,
    name          VARCHAR(128) NOT NULL,
    instance_id   BIGINT,
    system_key    VARCHAR(32),
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark        VARCHAR(500),
    delete_flag   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doc_folder_owner
    ON agent_document_folder (tenant_id, owner_user_id, parent_id)
    WHERE delete_flag = false;

CREATE UNIQUE INDEX IF NOT EXISTS uk_doc_folder_manual_name
    ON agent_document_folder (owner_user_id, COALESCE(parent_id, 0), name)
    WHERE delete_flag = false AND instance_id IS NULL AND system_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_doc_folder_instance
    ON agent_document_folder (owner_user_id, instance_id)
    WHERE delete_flag = false AND instance_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_doc_folder_system
    ON agent_document_folder (owner_user_id, system_key)
    WHERE delete_flag = false AND system_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_document_comment (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL DEFAULT 0,
    document_id    BIGINT       NOT NULL,
    parent_id      BIGINT,
    author_user_id BIGINT,
    author_name    VARCHAR(100),
    content        TEXT         NOT NULL,
    quoted_text    VARCHAR(500),
    status         VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    resolved_by    BIGINT,
    resolved_at    TIMESTAMP,
    remark         VARCHAR(500),
    delete_flag    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doc_comment_document
    ON agent_document_comment (document_id, status, created_at)
    WHERE delete_flag = false;

CREATE INDEX IF NOT EXISTS idx_doc_comment_parent
    ON agent_document_comment (parent_id)
    WHERE delete_flag = false;

-- One system "avatar documents" root per user that owns instance-created documents.
INSERT INTO agent_document_folder
    (tenant_id, owner_user_id, parent_id, name, system_key, created_by, updated_by)
SELECT DISTINCT d.tenant_id, u.id, NULL::bigint, '分身文档', 'INSTANCE_ROOT', d.created_by, d.created_by
FROM agent_document d
JOIN agent_user u ON u.username = d.created_by
WHERE d.delete_flag = 0
  AND d.instance_id > 0
  AND d.created_by IS NOT NULL
ON CONFLICT DO NOTHING;

-- One folder per (owner, instance), under the avatar-documents root.
INSERT INTO agent_document_folder
    (tenant_id, owner_user_id, parent_id, name, instance_id, created_by, updated_by)
SELECT d.tenant_id,
       u.id,
       r.id,
       COALESCE(NULLIF(i.name, ''), '实例 ' || d.instance_id::text),
       d.instance_id,
       d.created_by,
       d.created_by
FROM agent_document d
JOIN agent_user u ON u.username = d.created_by
JOIN agent_document_folder r
  ON r.owner_user_id = u.id
 AND r.system_key = 'INSTANCE_ROOT'
 AND r.delete_flag = false
LEFT JOIN agent_instance i ON i.id = d.instance_id
WHERE d.delete_flag = 0
  AND d.instance_id > 0
  AND d.created_by IS NOT NULL
GROUP BY d.tenant_id, u.id, r.id, d.instance_id, i.name, d.created_by
ON CONFLICT DO NOTHING;

UPDATE agent_document d
SET folder_id = f.id
FROM agent_user u, agent_document_folder f
WHERE u.username = d.created_by
  AND f.owner_user_id = u.id
  AND f.instance_id = d.instance_id
  AND f.delete_flag = false
  AND d.delete_flag = 0
  AND d.instance_id > 0
  AND d.folder_id IS NULL;

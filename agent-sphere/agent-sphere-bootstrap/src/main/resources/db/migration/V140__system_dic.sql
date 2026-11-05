-- ============================================================
-- V140: system dictionary + position dictionary seed + admin permissions
-- ============================================================

CREATE TABLE IF NOT EXISTS system_dic (
    id          BIGSERIAL PRIMARY KEY,
    dic_type    VARCHAR(64)  NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name_cn     VARCHAR(128) NOT NULL,
    name_en     VARCHAR(128) NOT NULL,
    sort_no     INT          NOT NULL DEFAULT 0,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark      VARCHAR(512),
    delete_flag BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_system_dic_code
    ON system_dic (dic_type, code) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_system_dic_type
    ON system_dic (dic_type, status, sort_no) WHERE delete_flag = FALSE;

-- 默认职位字典
INSERT INTO system_dic (dic_type, code, name_cn, name_en, sort_no, status)
SELECT 'POSITION', 'FINANCE', '财务专家', 'Finance Specialist', 1, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system_dic WHERE dic_type = 'POSITION' AND code = 'FINANCE' AND delete_flag = FALSE);

INSERT INTO system_dic (dic_type, code, name_cn, name_en, sort_no, status)
SELECT 'POSITION', 'RECRUITING', '招聘专家', 'Recruiting Specialist', 2, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system_dic WHERE dic_type = 'POSITION' AND code = 'RECRUITING' AND delete_flag = FALSE);

INSERT INTO system_dic (dic_type, code, name_cn, name_en, sort_no, status)
SELECT 'POSITION', 'LEGAL', '法务专家', 'Legal Specialist', 3, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system_dic WHERE dic_type = 'POSITION' AND code = 'LEGAL' AND delete_flag = FALSE);

INSERT INTO system_dic (dic_type, code, name_cn, name_en, sort_no, status)
SELECT 'POSITION', 'MARKETING', '市场专家', 'Marketing Specialist', 4, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system_dic WHERE dic_type = 'POSITION' AND code = 'MARKETING' AND delete_flag = FALSE);

INSERT INTO system_dic (dic_type, code, name_cn, name_en, sort_no, status)
SELECT 'POSITION', 'PRODUCT', '产品专家', 'Product Specialist', 5, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system_dic WHERE dic_type = 'POSITION' AND code = 'PRODUCT' AND delete_flag = FALSE);

INSERT INTO system_dic (dic_type, code, name_cn, name_en, sort_no, status)
SELECT 'POSITION', 'TECH', '技术专家', 'Technology Specialist', 6, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system_dic WHERE dic_type = 'POSITION' AND code = 'TECH' AND delete_flag = FALSE);

-- 字典管理权限（仅 ADMIN）
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT '字典管理', 'admin:dictionary', 'MENU', id, 8, '系统字典维护'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:dictionary');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '字典查看', 'admin:dictionary:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:dictionary'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:dictionary:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '字典创建', 'admin:dictionary:create', 'BUTTON', id, 2
FROM sys_permission WHERE code = 'admin:dictionary'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:dictionary:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '字典修改', 'admin:dictionary:update', 'BUTTON', id, 3
FROM sys_permission WHERE code = 'admin:dictionary'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:dictionary:update');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '字典删除', 'admin:dictionary:delete', 'BUTTON', id, 4
FROM sys_permission WHERE code = 'admin:dictionary'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:dictionary:delete');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code = 'ADMIN'
  AND p.code IN ('admin:dictionary', 'admin:dictionary:read',
                 'admin:dictionary:create', 'admin:dictionary:update',
                 'admin:dictionary:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);

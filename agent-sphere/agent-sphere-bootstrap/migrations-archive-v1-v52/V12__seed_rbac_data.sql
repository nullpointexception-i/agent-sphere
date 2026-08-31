-- Insert roles
INSERT INTO sys_role (name, code, description) VALUES ('管理员', 'ADMIN', '系统管理员，拥有全部权限');
INSERT INTO sys_role (name, code, description) VALUES ('普通用户', 'USER', '普通用户，拥有基本操作权限');
INSERT INTO sys_role (name, code, description) VALUES ('演示账户', 'DEMO', '演示账户，仅拥有只读和基本对话权限');

-- Insert permissions
INSERT INTO sys_permission (name, code, type, sort) VALUES ('用户管理', 'user', 'MENU', 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('修改密码', 'user:password:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'user'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('修改资料', 'user:profile:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'user'), 2);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('文档管理', 'document', 'MENU', 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档创建', 'document:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档查看', 'document:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档编辑', 'document:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档删除', 'document:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档分享', 'document:share', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 5);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('模型供应商', 'model', 'MENU', 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('供应商创建', 'model:provider:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('供应商查看', 'model:provider:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('供应商编辑', 'model:provider:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('供应商删除', 'model:provider:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥创建', 'model:apikey:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 5);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥查看', 'model:apikey:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 6);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥编辑', 'model:apikey:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 7);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥删除', 'model:apikey:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 8);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥设为当前', 'model:apikey:set-active', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 9);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('路由创建', 'model:route:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 10);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('路由查看', 'model:route:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 11);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('路由编辑', 'model:route:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 12);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('路由删除', 'model:route:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 13);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('能力管理', 'capability', 'MENU', 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('MCP创建', 'capability:mcp:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('MCP查看', 'capability:mcp:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('MCP编辑', 'capability:mcp:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('MCP删除', 'capability:mcp:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('Skill创建', 'capability:skill:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 5);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('Skill查看', 'capability:skill:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 6);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('Skill编辑', 'capability:skill:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 7);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('Skill删除', 'capability:skill:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 8);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('CLI创建', 'capability:cli:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 9);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('CLI查看', 'capability:cli:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 10);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('CLI编辑', 'capability:cli:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 11);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('CLI删除', 'capability:cli:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 12);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('内置能力查看', 'capability:builtin:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 13);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('实例管理', 'instance', 'MENU', 5);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例创建', 'instance:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例查看', 'instance:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例编辑', 'instance:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例删除', 'instance:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例绑定能力', 'instance:capability:bind', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 5);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例解绑能力', 'instance:capability:unbind', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 6);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('系统设置', 'admin', 'MENU', 6);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('设置查看', 'admin:settings:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('设置修改', 'admin:settings:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('AES密钥重新生成', 'admin:settings:regenerate-aes', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('角色查看', 'admin:role:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('角色分配', 'admin:role:assign', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 5);

-- ADMIN role gets all permissions
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT (SELECT id FROM sys_role WHERE code = 'ADMIN'), id FROM sys_permission;

-- USER role gets all except admin:settings:* and admin:role:*
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT (SELECT id FROM sys_role WHERE code = 'USER'), id FROM sys_permission
WHERE code NOT LIKE 'admin:%';

-- DEMO role gets read-only + basic chat permissions
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT (SELECT id FROM sys_role WHERE code = 'DEMO'), id FROM sys_permission
WHERE code IN (
    'document:read',
    'model:provider:read',
    'model:apikey:read',
    'model:route:read',
    'capability:mcp:read',
    'capability:skill:read',
    'capability:cli:read',
    'capability:builtin:read',
    'instance:read'
);

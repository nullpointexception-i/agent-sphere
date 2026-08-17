-- 插件安装包下载配置：值由应用内上传托管流程写入托管路由，或管理员手工填外链；留空则前端不展示下载入口。
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
SELECT 'plugin', 'plugin.download-url', '', false, 'Chrome 插件安装包下载地址（应用内上传托管，或填写外链；留空不展示入口）'
WHERE NOT EXISTS (SELECT 1 FROM agent_system_config WHERE config_key = 'plugin.download-url');

-- 自助注册用户初始化资源模板：JSON 数组，结构同 SSO 身份源资源模板；留空回落默认模板。
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
SELECT 'user', 'user.resource-template', '', false, '自助注册用户初始化资源模板（JSON，留空使用默认模板）'
WHERE NOT EXISTS (SELECT 1 FROM agent_system_config WHERE config_key = 'user.resource-template');
-- Chrome 插件应用市场（Chrome Web Store）下载地址：登录页/widget「插件下载」下拉的应用市场选项。
-- 系统管理 → 插件安装包 可修改。
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
SELECT 'plugin', 'plugin.store-url',
       'https://chromewebstore.google.com/detail/agentsphere/cpjaeggemhjndbnepjifgckodnlfhfkg?hl=zh-CN&utm_source=ext_sidebar',
       false, 'Chrome 插件应用市场（Chrome Web Store）下载地址；留空不展示应用市场选项'
WHERE NOT EXISTS (SELECT 1 FROM agent_system_config WHERE config_key = 'plugin.store-url');
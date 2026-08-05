-- SSO 回调地址基础 URL 迁入 settings（agent_system_config），
-- 替代原先 k8s ConfigMap 中的 BUUKLE_AGENT_SSO_BASEURL 环境变量。
-- 之后可在 系统管理 → 系统配置 修改 sso.base-url，即时生效。

INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
SELECT 'sso', 'sso.base-url', 'http://as.buukle.top', false, 'SSO 回调地址基础 URL'
WHERE NOT EXISTS (SELECT 1 FROM agent_system_config WHERE config_key = 'sso.base-url');

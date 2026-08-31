-- 归一化 plugin.download-url：存 apiBase 相对路由（前端按各自 apiBase 前缀拼接），
-- 消除历史写入中 /api/v1 双前缀（如 /api/v1/api/v1/system/config/plugin/download）导致的坏链接。
-- 外链（http/https）不受影响。
UPDATE agent_system_config
SET config_value = '/' || REGEXP_REPLACE(config_value, '^/api/v1(/api/v1)?', '')
WHERE config_key = 'plugin.download-url'
  AND config_value LIKE '/api/v1%';
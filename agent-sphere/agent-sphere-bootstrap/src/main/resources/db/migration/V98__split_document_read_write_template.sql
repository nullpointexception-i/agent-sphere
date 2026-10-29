-- ------------------------------------------------------------
-- V98: 文档工具拆分为读取/写入两个工具后，更新默认资源模板的实例提示词。
-- 只改默认模板，已初始化实例的 systemPrompt 不自动改写。
-- ------------------------------------------------------------
UPDATE agent_system_config
SET config_value = replace(
        replace(
            config_value,
            'check your document list with the doc tool',
            'use the document read tool to list, search, and read documents'
        ),
        'create or update the experience document accordingly',
        'use the document write tool to create, append, or patch the experience document accordingly'
    ),
    updated_at = NOW()
WHERE config_key = 'user.resource-template';

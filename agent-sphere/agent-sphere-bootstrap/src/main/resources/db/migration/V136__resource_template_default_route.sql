-- ============================================================
-- V136: mark DeepSeek as the default route in the resource template
-- ============================================================

UPDATE agent_system_config
SET config_value = replace(
        config_value,
        '"modelName":"deepseek-flash","company":"deepseek","weight":100,"supportsAttachment":true}',
        '"modelName":"deepseek-flash","company":"deepseek","weight":100,"supportsAttachment":true,"default":true}'
    )
WHERE config_key = 'user.resource-template'
  AND config_value LIKE '%"modelName":"deepseek-flash","company":"deepseek","weight":100,"supportsAttachment":true}%';

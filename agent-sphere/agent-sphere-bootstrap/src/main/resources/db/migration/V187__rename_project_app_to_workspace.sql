-- Rename the built-in project application to "Project Workspace".
UPDATE sys_i18n_text
SET text = '项目空间',
    updated_by = 'system',
    updated_at = now()
WHERE tenant_id = 0
  AND message_key = 'application.system.projects.name'
  AND locale = 'zh-CN'
  AND delete_flag = FALSE;

UPDATE sys_i18n_text
SET text = 'Project Workspace',
    updated_by = 'system',
    updated_at = now()
WHERE tenant_id = 0
  AND message_key = 'application.system.projects.name'
  AND locale = 'en-US'
  AND delete_flag = FALSE;

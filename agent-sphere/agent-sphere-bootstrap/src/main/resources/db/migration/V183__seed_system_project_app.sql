-- Seed the system project management application, scenes and bilingual copy.

INSERT INTO biz_application (
    tenant_id, app_key, name_key, description_key, default_locale, icon,
    app_type, entry_mode, entry_key, visibility, owner_user_id, sort_order, config, status
)
SELECT
    0, 'system-projects',
    'application.system.projects.name',
    'application.system.projects.description',
    'zh-CN', 'FolderKanban',
    'SYSTEM', 'NATIVE', 'PROJECTS', 'TENANT', 0, 10,
    '{"defaultAgentPolicy":"PROJECT_BOUND","contextScopes":["PROJECT","REQUIREMENT"]}'::jsonb,
    'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM biz_application
    WHERE tenant_id = 0 AND app_key = 'system-projects' AND delete_flag = FALSE
);

INSERT INTO biz_application_scene (
    tenant_id, application_id, scene_key, name_key, description_key, default_locale,
    scene_type, config, sort_order, status
)
SELECT
    0, a.id, s.scene_key, s.name_key, s.description_key, 'zh-CN',
    s.scene_type, s.config::jsonb, s.sort_order, 'ACTIVE'
FROM biz_application a
JOIN (VALUES
    ('project-list',
     'application.system.projects.scene.project-list.name',
     'application.system.projects.scene.project-list.description',
     'PAGE',
     '{"renderMode":"NATIVE","entryKey":"PROJECT_LIST"}', 10),
    ('requirement-board',
     'application.system.projects.scene.requirement-board.name',
     'application.system.projects.scene.requirement-board.description',
     'PAGE',
     '{"renderMode":"NATIVE","entryKey":"REQUIREMENT_BOARD"}', 20),
    ('project-qa',
     'application.system.projects.scene.project-qa.name',
     'application.system.projects.scene.project-qa.description',
     'QA',
     '{"welcomeKey":"application.system.projects.scene.project-qa.welcome","suggestionKeys":["application.system.projects.scene.project-qa.suggestion.1","application.system.projects.scene.project-qa.suggestion.2"],"contextScopes":["APPLICATION","PROJECT","REQUIREMENT"]}', 30),
    ('weekly-report',
     'application.system.projects.scene.weekly-report.name',
     'application.system.projects.scene.weekly-report.description',
     'PERIODIC',
     '{"enabled":false,"cron":"0 18 * * FRI","timezone":"Asia/Shanghai","goalTemplateKey":"application.system.projects.scene.weekly-report.goal","runMode":"READ"}', 40)
) AS s(scene_key, name_key, description_key, scene_type, config, sort_order)
ON a.tenant_id = 0 AND a.app_key = 'system-projects' AND a.delete_flag = FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM biz_application_scene sc
    WHERE sc.application_id = a.id AND sc.scene_key = s.scene_key AND sc.delete_flag = FALSE
);

INSERT INTO sys_i18n_text (tenant_id, message_key, locale, text, status)
VALUES
    (0, 'application.system.projects.name', 'zh-CN', '项目管理', 'ACTIVE'),
    (0, 'application.system.projects.name', 'en-US', 'Project Management', 'ACTIVE'),
    (0, 'application.system.projects.description', 'zh-CN', '项目、需求、成员与项目分身', 'ACTIVE'),
    (0, 'application.system.projects.description', 'en-US', 'Projects, requirements, members, and project agents', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-list.name', 'zh-CN', '项目列表', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-list.name', 'en-US', 'Projects', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-list.description', 'zh-CN', '查看和管理项目', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-list.description', 'en-US', 'View and manage projects', 'ACTIVE'),
    (0, 'application.system.projects.scene.requirement-board.name', 'zh-CN', '需求看板', 'ACTIVE'),
    (0, 'application.system.projects.scene.requirement-board.name', 'en-US', 'Requirement Board', 'ACTIVE'),
    (0, 'application.system.projects.scene.requirement-board.description', 'zh-CN', '按状态查看和推进需求', 'ACTIVE'),
    (0, 'application.system.projects.scene.requirement-board.description', 'en-US', 'Track and move requirements by status', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.name', 'zh-CN', '项目问答', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.name', 'en-US', 'Project Q&A', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.description', 'zh-CN', '围绕项目与需求向分身提问', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.description', 'en-US', 'Ask the project agent about projects and requirements', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.welcome', 'zh-CN', '可以问我项目进度、需求风险、成员分工。', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.welcome', 'en-US', 'Ask me about project progress, requirement risks, or ownership.', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.suggestion.1', 'zh-CN', '本周有哪些高风险需求？', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.suggestion.1', 'en-US', 'Which requirements are high risk this week?', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.suggestion.2', 'zh-CN', '汇总待评审需求', 'ACTIVE'),
    (0, 'application.system.projects.scene.project-qa.suggestion.2', 'en-US', 'Summarize requirements pending review', 'ACTIVE'),
    (0, 'application.system.projects.scene.weekly-report.name', 'zh-CN', '项目周报', 'ACTIVE'),
    (0, 'application.system.projects.scene.weekly-report.name', 'en-US', 'Weekly Project Report', 'ACTIVE'),
    (0, 'application.system.projects.scene.weekly-report.description', 'zh-CN', '每周汇总项目进展与风险', 'ACTIVE'),
    (0, 'application.system.projects.scene.weekly-report.description', 'en-US', 'Weekly project progress and risk summary', 'ACTIVE'),
    (0, 'application.system.projects.scene.weekly-report.goal', 'zh-CN', '汇总本周项目进展与风险', 'ACTIVE'),
    (0, 'application.system.projects.scene.weekly-report.goal', 'en-US', 'Summarize this week''s project progress and risks', 'ACTIVE')
ON CONFLICT (tenant_id, message_key, locale) WHERE delete_flag = FALSE DO NOTHING;

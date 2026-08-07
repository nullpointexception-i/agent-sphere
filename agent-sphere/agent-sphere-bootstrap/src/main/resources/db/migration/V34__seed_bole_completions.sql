-- V34: Bole 集成 completions seed（幂等，固定 id 1-7）
-- 契约：Bole 侧 system_settings.agent_sphere_completions 维护 code→id 映射，id 必须稳定
-- model_route_id 先置 NULL：模型路由由管理员手动配置后，再绑定（见集成文档运维步骤）
-- input_schema/output_schema 仅存储，as 不校验

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(1, '简历解析', '从简历信息提取结构化字段（Bole: resume_parse）', NULL, NULL,
 '{"type":"object","required":["resumeText"],"properties":{"resumeText":{"type":"string","description":"简历原始文本"},"candidateId":{"type":"integer","description":"Bole 候选人 id"}}}'::jsonb,
 '{"type":"object","required":["name","summary"],"properties":{"name":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"},"gender":{"type":"string"},"age":{"type":"integer"},"education":{"type":"array","items":{"type":"object","properties":{"school":{"type":"string"},"degree":{"type":"string"},"major":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"}}}},"workExperience":{"type":"array","items":{"type":"object","properties":{"company":{"type":"string"},"title":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"description":{"type":"string"}}}},"skills":{"type":"array","items":{"type":"string"}},"summary":{"type":"string"}}}'::jsonb,
 '{"temperature":0.1}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(2, '5 维匹配', '按技能/职位/行业/目标公司/年限 5 维评分（Bole: five_dim_match，当前无生产调用）', NULL, NULL,
 '{"type":"object","required":["candidateProfile","jobRequirement"],"properties":{"candidateProfile":{"type":"object","description":"候选人画像"},"jobRequirement":{"type":"object","description":"职位要求"}}}'::jsonb,
 '{"type":"object","required":["scores","overall"],"properties":{"scores":{"type":"object","properties":{"skill":{"type":"integer","minimum":0,"maximum":100},"position":{"type":"integer","minimum":0,"maximum":100},"industry":{"type":"integer","minimum":0,"maximum":100},"targetCompany":{"type":"integer","minimum":0,"maximum":100},"years":{"type":"integer","minimum":0,"maximum":100}}},"overall":{"type":"integer","minimum":0,"maximum":100},"comment":{"type":"string"}}}'::jsonb,
 '{"temperature":0.2}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(3, 'AI 触达话术', '生成面向候选人的暖场触达话术（Bole: outreach）', NULL, NULL,
 '{"type":"object","required":["candidate"],"properties":{"candidate":{"type":"object","description":"候选人信息"},"job":{"type":"object","description":"职位信息"},"channel":{"type":"string","description":"触达渠道，如 wechat/email"}}}'::jsonb,
 '{"type":"object","required":["message"],"properties":{"message":{"type":"string","description":"触达话术文案"}}}'::jsonb,
 '{"temperature":0.7}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(4, '自然语言搜索', '将自然语言寻访需求解析为结构化筛选条件（Bole: nl_search）', NULL, NULL,
 '{"type":"object","required":["query"],"properties":{"query":{"type":"string","description":"自然语言寻访需求"}}}'::jsonb,
 '{"type":"object","properties":{"industry":{"type":"string"},"skills":{"type":"array","items":{"type":"string"}},"city":{"type":"string"},"education":{"type":"string"},"minYears":{"type":"integer"},"maxYears":{"type":"integer"},"minSalary":{"type":"integer"},"summary":{"type":"string"}}}'::jsonb,
 '{"temperature":0.1}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(5, '组织收集', '根据目标公司整理组织架构与联系信息（Bole: org_collect，当前纯 mock）', NULL, NULL,
 '{"type":"object","required":["company"],"properties":{"company":{"type":"string","description":"目标公司名称"},"depth":{"type":"integer","description":"收集层级深度"}}}'::jsonb,
 '{"type":"object","properties":{"departments":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"manager":{"type":"string"},"contact":{"type":"string"}}}},"note":{"type":"string"}}}'::jsonb,
 '{"temperature":0.1}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(6, '评估报告推荐理由', '面向客户生成推荐理由（Bole: recommend_reason）', NULL, NULL,
 '{"type":"object","required":["candidate","position"],"properties":{"candidate":{"type":"object","description":"候选人画像"},"position":{"type":"object","description":"职位要求"},"customer":{"type":"object","description":"客户信息"}}}'::jsonb,
 '{"type":"object","required":["reason"],"properties":{"reason":{"type":"string"},"highlights":{"type":"array","items":{"type":"string"}},"risks":{"type":"array","items":{"type":"string"}}}}'::jsonb,
 '{"temperature":0.3}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(7, 'AI 面试题', '按岗位画像与简历差距生成面试题（Bole: interview_questions）', NULL, NULL,
 '{"type":"object","required":["jobProfile","resume"],"properties":{"jobProfile":{"type":"object","description":"岗位画像"},"resume":{"type":"object","description":"候选人简历"}}}'::jsonb,
 '{"type":"object","required":["questions"],"properties":{"questions":{"type":"array","items":{"type":"object","properties":{"dimension":{"type":"string"},"type":{"type":"string","enum":["behavioral","technical","situational"]},"content":{"type":"string"}}}}}}'::jsonb,
 '{"temperature":0.6}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

-- prompts（每个 completions 一个 version=1，显式 id 与 completionsId 对齐）
INSERT INTO agent_completions_prompt (id, completions_id, version, prompt_system, prompt_user, status, tenant_id, created_by)
VALUES
(1, 1, 1, '你是资深招聘顾问，从简历信息提取结构化字段。只输出 JSON，不要额外说明。', '{{input}}', 'ACTIVE', 0, 'system'),
(2, 2, 1, '你是资深猎头，请按 技能/职位/行业/目标公司/年限 5 维对候选人匹配度评分(0-100)。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(3, 3, 1, '你是招聘顾问，为候选人生成自然真诚的暖场触达话术。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(4, 4, 1, '你将自然语言寻访需求解析为结构化筛选条件(industry/skills/city/education/minYears/maxYears/minSalary/summary)。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(5, 5, 1, '你根据目标公司整理组织架构与联系信息。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(6, 6, 1, '你面向客户生成候选人推荐理由，突出亮点并提示风险。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(7, 7, 1, '你根据岗位画像与候选人简历差距生成面试题(questions:[{dimension,type,content}])。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

-- 回填 active_prompt_id（幂等：仅当为空时）
UPDATE agent_completions SET active_prompt_id = id WHERE id BETWEEN 1 AND 7 AND active_prompt_id IS NULL;

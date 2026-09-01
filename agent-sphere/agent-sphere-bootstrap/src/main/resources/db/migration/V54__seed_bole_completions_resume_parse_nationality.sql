-- V54: 历史补全 resume_parse 的 output_schema，新增 nationality / currentCountry（Bole 简历导入-国籍/当前国家）。
-- 运行时按 business_type + created_by(调用用户) 匹配，存在平台行与每用户私有副本，需全量更新。
-- 本迁移 = V53 版本 + nationality + currentCountry。整体替换（幂等，可重复执行）。
UPDATE agent_completions
SET output_schema = '{"type":"object","required":["name","summary"],"properties":{"name":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"},"gender":{"type":"string"},"age":{"type":"integer"},"education":{"type":"array","items":{"type":"object","properties":{"school":{"type":"string"},"degree":{"type":"string"},"major":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"}}}},"workExperience":{"type":"array","items":{"type":"object","properties":{"company":{"type":"string"},"title":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"description":{"type":"string"}}}},"currentCity":{"type":"string","description":"现居城市/所在地名称，如 上海"},"nationality":{"type":"string","description":"候选人国籍，如 中国/China"},"currentCountry":{"type":"string","description":"当前所在国家，如 中国/China"},"projectExperience":{"type":"array","items":{"type":"object","properties":{"projectName":{"type":"string"},"role":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"responsibilities":{"type":"string"}}}},"skills":{"type":"array","items":{"type":"string"}},"summary":{"type":"string"}}}'::jsonb,
    updated_at = NOW()
WHERE business_type = 'resume_parse'
  AND delete_flag = 0
  AND status = 'ACTIVE';
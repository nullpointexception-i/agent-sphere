-- V53: 历史补全 resume_parse 的 output_schema，新增 currentCity（Bole 简历导入-现居城市/所在地）。
-- 运行时按 business_type + created_by(调用用户) 匹配，存在平台行与每用户私有副本，需全量更新。
-- 与 V50（新增 projectExperience）保持连续：本迁移 = V50 版本 + currentCity。
-- 整体替换（幂等，可重复执行）。
UPDATE agent_completions
SET output_schema = '{"type":"object","required":["name","summary"],"properties":{"name":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"},"gender":{"type":"string"},"age":{"type":"integer"},"education":{"type":"array","items":{"type":"object","properties":{"school":{"type":"string"},"degree":{"type":"string"},"major":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"}}}},"workExperience":{"type":"array","items":{"type":"object","properties":{"company":{"type":"string"},"title":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"description":{"type":"string"}}}},"currentCity":{"type":"string","description":"现居城市/所在地名称，如 上海"},"projectExperience":{"type":"array","items":{"type":"object","properties":{"projectName":{"type":"string"},"role":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"responsibilities":{"type":"string"}}}},"skills":{"type":"array","items":{"type":"string"}},"summary":{"type":"string"}}}'::jsonb,
    updated_at = NOW()
WHERE business_type = 'resume_parse'
  AND delete_flag = 0
  AND status = 'ACTIVE';
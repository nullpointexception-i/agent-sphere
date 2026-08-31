-- V50: 历史补全 resume_parse 的 output_schema，新增 projectExperience（Bole 项目经历导入）。
-- 运行时按 business_type + created_by(调用用户) 匹配，存在系统行与每用户私有副本，需全量更新。
-- 整体替换（幂等，可重复执行）。
UPDATE agent_completions
SET output_schema = '{"type":"object","required":["name","summary"],"properties":{"name":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"},"gender":{"type":"string"},"age":{"type":"integer"},"education":{"type":"array","items":{"type":"object","properties":{"school":{"type":"string"},"degree":{"type":"string"},"major":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"}}}},"workExperience":{"type":"array","items":{"type":"object","properties":{"company":{"type":"string"},"title":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"description":{"type":"string"}}}},"projectExperience":{"type":"array","items":{"type":"object","properties":{"projectName":{"type":"string"},"role":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"responsibilities":{"type":"string"}}}},"skills":{"type":"array","items":{"type":"string"}},"summary":{"type":"string"}}}'::jsonb,
    updated_at = NOW()
WHERE business_type = 'resume_parse'
  AND delete_flag = 0
  AND status = 'ACTIVE';

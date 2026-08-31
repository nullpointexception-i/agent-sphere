-- V51: 为历史库补全 Bole 岗位画像生成 completions 资源（profile_generate）
-- 运行时按 business_type + created_by(=AS 调用者用户名) + ACTIVE 匹配；
-- 平台 'system' 行仅作组织/模板展示，真实调用走各用户私有副本（created_by = 用户用户名）。
-- 本迁移幂等：已存在同名(business_type+created_by)非删除记录则跳过。
-- id 由 BIGSERIAL 自增，迁移不指定显式 id。
DO $$
DECLARE
    r RECORD;
    cid BIGINT;
    pid BIGINT;
BEGIN
    -- 目标 owner = 平台 system + 已开通 Bole 资源集（以 resume_parse 私有副本作为标记）的用户
    FOR r IN
        SELECT DISTINCT created_by AS owner
        FROM agent_completions
        WHERE business_type = 'resume_parse'
          AND delete_flag = 0
          AND status = 'ACTIVE'
          AND created_by IS NOT NULL
        UNION
        SELECT 'system'
    LOOP
        IF EXISTS (SELECT 1 FROM agent_completions
                   WHERE business_type = 'profile_generate'
                     AND created_by = r.owner
                     AND delete_flag = 0) THEN
            CONTINUE;
        END IF;

        INSERT INTO agent_completions
            (name, description, model_route_id, active_prompt_id, input_schema, output_schema, config,
             business_type, status, delete_flag, tenant_id, created_by)
        VALUES
            ('岗位画像生成', '按 JD 与基础信息生成岗位 9 区块画像（Bole: profile_generate）', NULL, NULL,
             '{"type":"object","required":["title","jd"],"properties":{"title":{"type":"string","description":"职位名称"},"jd":{"type":"string","description":"职位描述"},"department":{"type":"string","description":"所属部门"},"salaryMin":{"type":"number","description":"最低年薪(元)"},"salaryMax":{"type":"number","description":"最高年薪(元)"},"workCity":{"type":"string","description":"工作城市"}}}'::jsonb,
             '{"type":"object","required":["hard","target","experience","ability","character","keywords","search","preference","aiSummary"],"properties":{"positionId":{"type":"integer"},"hard":{"type":"object","properties":{"degree":{"type":"string","description":"学历"},"years":{"type":"string","description":"经验年限"},"industry":{"type":"string","description":"行业"},"skills":{"type":"array","items":{"type":"string"}},"background":{"type":"string","description":"背景"}}},"target":{"type":"object","properties":{"tier":{"type":"string"},"industry":{"type":"string"},"level":{"type":"string"},"salary":{"type":"string"},"companies":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"tier":{"type":"string"},"fit":{"type":"string"},"reason":{"type":"string"}}}}}},"experience":{"type":"object","properties":{"years":{"type":"string"},"manageYears":{"type":"string"},"industryExp":{"type":"string"},"stability":{"type":"string"}}},"ability":{"type":"object","properties":{"coreSkills":{"type":"array","items":{"type":"string"}},"plusSkills":{"type":"array","items":{"type":"string"}}}},"character":{"type":"object","properties":{"traits":{"type":"array","items":{"type":"string"}}}},"keywords":{"type":"array","items":{"type":"object","properties":{"keyword":{"type":"string"},"category":{"type":"string"},"priority":{"type":"string"},"remark":{"type":"string"}}}},"search":{"type":"object","properties":{"desc":{"type":"string"},"linkedin":{"type":"string"},"maimai":{"type":"string"},"boss":{"type":"string"},"liepin":{"type":"string"},"skillWords":{"type":"array","items":{"type":"string"}},"companyWords":{"type":"array","items":{"type":"string"}},"excludeWords":{"type":"array","items":{"type":"string"}},"platforms":{"type":"array","items":{"type":"string"}}}},"preference":{"type":"object","properties":{"interviewerFocus":{"type":"string"},"highFreq":{"type":"array","items":{"type":"string"}},"keywords":{"type":"array","items":{"type":"string"}},"salarySensitivity":{"type":"string"},"interviewStyle":{"type":"string"},"eliminate":{"type":"array","items":{"type":"string"}},"sinkFeedback":{"type":"string"}}},"aiSummary":{"type":"string","description":"画像精髓总结"}}}'::jsonb,
             '{"temperature":0.3,"thinking":false}'::jsonb,
             'profile_generate', 'ACTIVE', 0, 0, r.owner)
        RETURNING id INTO cid;

        INSERT INTO agent_completions_prompt
            (completions_id, version, prompt_system, prompt_user, status, delete_flag, tenant_id, created_by)
        VALUES
            (cid, 1,
             '你是资深招聘顾问。根据职位名称、职位描述与基础信息，生成标准化的岗位画像，包含 9 个区块：hard(硬性要求)、target(目标人选画像)、experience(经验背景)、ability(能力)、character(特质)、keywords(关键词)、search(寻访线索与渠道)、preference(考察偏好)、aiSummary(画像精髓总结)。请严格按 JSON Schema 输出，只输出 JSON，不要额外说明。',
             '{{input}}', 'ACTIVE', 0, 0, r.owner)
        RETURNING id INTO pid;

        UPDATE agent_completions SET active_prompt_id = pid, updated_at = NOW() WHERE id = cid;
    END LOOP;
END $$;

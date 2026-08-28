-- V52: 为历史库 profile_generate 补全全量职位上下文入参（input_schema）并改写 prompt_system
-- 仅 UPDATE 已存在的 profile_generate 行（平台 system 行 + 各用户私有副本，delete_flag = 0）。
-- 不改动已执行的 V51；幂等：重复执行结果一致（按 business_type 无条件覆盖为新值）。
DO $$
DECLARE
    r RECORD;
    new_input jsonb  := '{"type":"object","required":["title","jd"],"properties":{"clientName":{"type":"string","description":"客户名称"},"title":{"type":"string","description":"职位名称"},"jd":{"type":"string","description":"职位描述"},"department":{"type":"string","description":"所属部门"},"salaryMin":{"type":"number","description":"最低年薪(元)"},"salaryMax":{"type":"number","description":"最高年薪(元)"},"workCity":{"type":"string","description":"工作城市"},"jobCategory":{"type":"string","description":"职能/行业"},"priority":{"type":"string","description":"优先级"},"recruitType":{"type":"string","description":"招聘类型"},"recruitCount":{"type":"integer","description":"招聘人数"},"candidateNationality":{"type":"string","description":"目标候选人国籍"},"country":{"type":"string","description":"国家/地区"},"salaryType":{"type":"string","description":"薪资类型"},"salaryUnit":{"type":"string","description":"薪资单位"},"feeAmount":{"type":"string","description":"服务费"},"paymentMethod":{"type":"string","description":"付款方式"},"status":{"type":"string","description":"职位状态"},"warrantyPeriod":{"type":"integer","description":"保证期(天)"},"interviewRounds":{"type":"integer","description":"面试轮次"},"budgetVsMarket":{"type":"string","description":"预算对比市场"},"expectedJoin":{"type":"string","description":"期望到岗时间"},"expectedFill":{"type":"string","description":"期望完成时间"},"remarks":{"type":"string","description":"备注"}}}'::jsonb;
    new_system text := '你是资深招聘顾问。根据职位基础信息 JSON (input) 生成标准化的岗位画像，包含 9 个区块：hard(硬性要求)、target(目标人选画像)、experience(经验背景)、ability(能力)、character(特质)、keywords(关键词)、search(寻访线索与渠道)、preference(考察偏好)、aiSummary(画像精髓总结)。input 可能包含：clientName(客户名称)、title(职位名称)、jd(职位描述)、department(部门)、jobCategory(职能/行业)、workCity(工作城市)、country(国家/地区)、salaryMin~salaryMax(年薪范围，单位元)、salaryType/salaryUnit(薪资类型/单位)、priority(优先级)、recruitType(招聘类型)、recruitCount(招聘人数)、candidateNationality(目标候选人国籍)、feeAmount(服务费)、paymentMethod(付款方式)、warrantyPeriod(保证期/天)、interviewRounds(面试轮次)、status(职位状态)、budgetVsMarket(预算对比市场)、expectedJoin(期望到岗)、expectedFill(期望完成)、remarks(备注)。请严格按 JSON Schema 输出，只输出 JSON，不要额外说明。';
BEGIN
    FOR r IN
        SELECT id, active_prompt_id
        FROM agent_completions
        WHERE business_type = 'profile_generate'
          AND delete_flag = 0
    LOOP
        UPDATE agent_completions
           SET input_schema = new_input,
               updated_at   = NOW()
         WHERE id = r.id;

        IF r.active_prompt_id IS NOT NULL THEN
            UPDATE agent_completions_prompt
               SET prompt_system = new_system,
                   updated_at    = NOW()
             WHERE completions_id = r.id
               AND id             = r.active_prompt_id
               AND delete_flag    = 0;
        END IF;
    END LOOP;
END $$;

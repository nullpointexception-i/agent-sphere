-- V41: 测试数据 - 一条测试任务 + 一条任务产物（用于「任务产物」页面效果验证）
-- created_by=admin：超管查看时不受行级归属过滤；普通用户如需可见请改为其用户名
WITH t AS (
    INSERT INTO agent_task (goal, status, result_json, tenant_id, created_by)
    VALUES (
        '整理候选人张三的公开画像并输出结构化简历摘要',
        'COMPLETED',
        $${
  "candidate": {
    "name": "张三",
    "title": "高级前端工程师",
    "company": "某互联网公司",
    "years": 6,
    "skills": ["React", "TypeScript", "Node.js"]
  },
  "summary": "6 年前端经验，熟悉 React 技术栈，近期主导过大型后台系统重构。",
  "highlights": ["首屏耗时 4s→1.8s 的优化", "团队技术分享 12 次"],
  "sources": ["https://example.com/profile/zhangsan"]
}$$::jsonb,
        0,
        'admin'
    )
    RETURNING id
)
INSERT INTO agent_task_artifact (task_id, artifact_type, content, schema_ref, run_id, status, created_by)
SELECT id, 'task_contract',
       $${
  "candidate": {
    "name": "张三",
    "title": "高级前端工程师",
    "company": "某互联网公司",
    "years": 6,
    "skills": ["React", "TypeScript", "Node.js"]
  },
  "summary": "6 年前端经验，熟悉 React 技术栈，近期主导过大型后台系统重构。",
  "highlights": ["首屏耗时 4s→1.8s 的优化", "团队技术分享 12 次"],
  "sources": ["https://example.com/profile/zhangsan"]
}$$,
       'task/contract/candidate-profile-v1',
       1,
       'ACTIVE',
       'admin'
FROM t;

-- 历史数据 seed：默认新用户模板模型由 deepseek-v4-flash 调整为 deepseek-v4-flash-vision-exp。
-- 1) agent_model_route.model_name 全量替换（引用 model_route_id 的 completions/instance/session 自动跟随，无需改 id）。
-- 2) 该视觉模型路由打开 supports_attachment，使其可接收聊天图片/截图观察（旧模板开通过的用户同享）。
UPDATE agent_model_route
SET model_name = 'deepseek-v4-flash-vision-exp'
WHERE model_name = 'deepseek-v4-flash';

UPDATE agent_model_route
SET supports_attachment = TRUE
WHERE model_name = 'deepseek-v4-flash-vision-exp'
  AND supports_attachment = FALSE;
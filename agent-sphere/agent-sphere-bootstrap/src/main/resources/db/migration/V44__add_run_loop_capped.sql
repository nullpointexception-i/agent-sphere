-- 任务 run 命中循环次数上限的强收口标记：任务守卫据此判失败，避免"半成品"冒充完成
ALTER TABLE agent_run ADD COLUMN loop_capped SMALLINT NOT NULL DEFAULT 0;
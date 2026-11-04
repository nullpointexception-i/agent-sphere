-- ============================================================
-- V116：run 级主模型路由（model_route_id）
--
-- 背景：挂起恢复（wait / LLM_RETRY / 澄清 / 崩溃恢复）一律用 validator.validate(sessionId, null)
-- 从实例配置重新解析路由。如果实例是「按 weight 从全部路由里选」，恢复时甚至可能换一个模型；
-- 管理员中途改实例路由也会让同一个 run 前后用不同模型。
--
-- 现在：run 首次进入时 pin 住实际解析到的路由，之后所有恢复入口都用这条；
-- 只有 pinned 路由被删/停用时才回落到实例当前配置（RuntimeValidator 已有回落逻辑）。
-- ============================================================

ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS model_route_id BIGINT;

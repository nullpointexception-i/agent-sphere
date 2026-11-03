-- ============================================================
-- V114：浏览器指令按"连接"定向投递（设备审计字段）
--
-- 背景：服务端原先按 username 广播浏览器指令，同一账号在多台浏览器登录时，
-- 谁的扩展先回包就由谁执行（线上出现"命令跑到另一个浏览器、目标站点未登录"）。
-- 现在改为：连接登记在 Redis，指令只投给「用户选定的设备」或「最新连接」。
--
-- 本迁移只补审计字段：这条工具调用是哪台设备、从哪个来源端执行的。
-- 在线连接状态不落库（临时数据，Redis 心跳维护，重启后 15s 内自愈）。
-- ============================================================

ALTER TABLE agent_tool_call_record ADD COLUMN IF NOT EXISTS device_id   VARCHAR(64);
ALTER TABLE agent_tool_call_record ADD COLUMN IF NOT EXISTS device_name VARCHAR(128);
ALTER TABLE agent_tool_call_record ADD COLUMN IF NOT EXISTS exec_source VARCHAR(16);

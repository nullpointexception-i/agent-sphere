-- ============================================================
-- agent_instance 会话来源开关（V89）
--
-- 「允许发起会话」此前没有开关，谁能在哪个入口建会话/发消息无法按实例控制。
-- 这里加两个来源维度：
--   allow_console_session —— 主站（agent-sphere-ui）
--   allow_widget_session  —— 嵌入式 widget（agent-sphere-copilot-widget）
--
-- 默认值只对**新建**实例生效：主站放行、widget 禁止。
-- 存量实例由下面的 UPDATE 按 businessType 回填 —— 带业务域的实例是外部 API 用来跑
-- 目标驱动任务的（见 resolveInstanceByBusinessType），widget 里本就不该手聊；
-- 不带业务域的是普通聊天实例，保持现状放行。
--
-- 服务端在 createSession / chat 两处按来源强校验（SessionSurfacePolicy），
-- 缺失来源信号（内部调用，如任务路径）一律放行。
-- ============================================================
ALTER TABLE agent_instance
    ADD COLUMN IF NOT EXISTS allow_console_session BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS allow_widget_session  BOOLEAN NOT NULL DEFAULT FALSE;

-- 存量回填：无业务域（或纯空白）的实例放行 widget；带业务域的保持禁止。
UPDATE agent_instance
   SET allow_widget_session = (business_type IS NULL OR btrim(business_type) = '')
 WHERE delete_flag = 0;

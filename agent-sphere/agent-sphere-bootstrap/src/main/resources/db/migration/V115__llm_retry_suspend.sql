-- ============================================================
-- V115：LLM 瞬时失败的挂起重试（LLM_RETRY）
--
-- 背景：5xx / 连接超时原先只在同轮内 sleep 重试（1s/2s/4s，约 7 秒窗口），
-- 上游 busy 持续几十秒时必然打穿（线上 503 持续约 3 分钟，多个 run 同时 FAILED）。
-- 现在改为梯度退避 + 挂起：不占执行资源，到点由现有 wait 调度器唤醒重跑同一轮。
--
-- 复用 agent_pending_wait，用 kind 区分：
--   WAIT       = wait 内置工具（原有语义，唤醒时补工具结果 + 合成 user）
--   LLM_RETRY  = 上游瞬时失败（唤醒时不补工具结果、不追加合成 user）
-- ============================================================

ALTER TABLE agent_pending_wait ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'WAIT';
ALTER TABLE agent_pending_wait ADD COLUMN IF NOT EXISTS error_status INT;

CREATE INDEX IF NOT EXISTS idx_pending_wait_kind
    ON agent_pending_wait (kind, status, wake_at)
    WHERE delete_flag = 0;

-- run 上冗余一份重试状态：列表/详情不必 join 挂起表就能渲染「重试中 k/N」
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS wait_kind VARCHAR(16);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS retry_attempt INT NOT NULL DEFAULT 0;

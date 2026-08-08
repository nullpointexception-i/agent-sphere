-- V37: agent_sso_identity 增加第三方登录用户名（display_subject）
-- 用于右上角展示 provider@username（如 bole@elvin）；OIDC sub 仍存于 subject 保持身份匹配稳定

ALTER TABLE agent_sso_identity ADD COLUMN IF NOT EXISTS display_subject VARCHAR(512);

-- ============================================================
-- V90：sso.base-url 的种子默认值改为 https
--
-- 背景：V30 把 sso.base-url 种成 http://as.buukle.top。域名切到 https 后，authorize 会把
-- redirect_uri 拼成 http://as.buukle.top/api/v1/auth/sso/callback，IdP 直接 400
-- （redirect_uri 不匹配）。之后即使改对了，token 交换与 id_token 的 iss 校验也要求全链路 https。
--
-- 为什么新增迁移而不是改 V30：已应用的迁移不可改写（会影响校验和与新老环境的执行顺序）。
--
-- 只改「仍是旧默认」的行：管理员自定义过的值不动，生产已手工改成 https 时本语句是 no-op。
-- ============================================================
UPDATE agent_system_config
   SET config_value = 'https://as.buukle.top'
 WHERE config_key = 'sso.base-url'
   AND config_value = 'http://as.buukle.top';

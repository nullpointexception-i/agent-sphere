-- ============================================================
-- V91：Chrome 插件最低要求版本（popup 升级提示）
--
-- 背景：扩展通过「Load unpacked」安装（GitHub Release 附件 zip），manifest 里没有 update_url，
-- Chrome 不会自动更新。域名切 https 后，未更新的扩展仍用 storage 里的 http backendUrl，
-- 在 https 页面上会被混合内容策略拦掉 —— 这是一种「用户不更新就不可用」的变更。
--
-- 本键让后端告诉扩展「最低要求版本」：popup 用 getManifest().version 比较，低于它时展示升级提示。
-- 注意：发布包的版本由 deploy.yml 从 tag 生成（v1.0.NNN-alpha → 1.0.NNN），
-- 与仓库里 manifest.json 的字面版本无关。
--
-- 留空 = 不提示。每次含破坏性变更的扩展发布，手动把本值调到新版本号。
-- ============================================================
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
VALUES ('plugin', 'plugin.min-extension-version', '1.0.118', false,
        'Chrome 插件最低要求版本（低于它的扩展会在 popup 提示升级；留空=不提示）。'
        '发布包版本由 deploy.yml 从 tag 生成：v1.0.NNN-alpha → 1.0.NNN')
ON CONFLICT (config_key) DO NOTHING;

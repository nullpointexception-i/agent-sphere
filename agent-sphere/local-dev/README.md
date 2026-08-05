# 本地开发：模拟 bole 用户测试 widget / SSO

用于本地完整走一遍「第三方 bole 用户 → OIDC SSO → AgentSphere」链路。

## 1. 本地环境

1. **中间件**：`cd agent-sphere/agent-docker-middleware && docker compose up -d`
   （Postgres + Redis；volume 路径硬编码 macOS，非 Mac 需改/删）。
2. **后端**：`cd agent-sphere && mvn -pl agent-sphere-bootstrap spring-boot:run -am`（:8080）。
   - 迁移 V27–V30 自动执行。⚠️ V30 会把 `sso.base-url` 种子为 `http://as.buukle.top`，
     本地改回本机后再重启后端：
     ```sql
     UPDATE agent_system_config SET config_value='http://localhost:8080' WHERE config_key='sso.base-url';
     ```
3. **widget**：`cd agent-sphere-copilot-widget && npm install && npm run dev`（:5173，`/api` 代理到 :8080）。
4. **主 UI（可选）**：`cd agent-sphere-ui && npm run dev`（:8000）。

## 2. Mock OIDC IdP（模拟 bole 认证源）

```bash
cd agent-sphere/local-dev
node mock-oidc-server.mjs        # :9000，issuer=http://localhost:9000
```

配置后端身份源（直接 SQL 插入；明文 secret 后端有兜底，可正常换 token）：

```sql
INSERT INTO agent_identity_provider
(code, type, name, issuer, client_id, client_secret,
 authorization_endpoint, token_endpoint, jwks_url,
 scopes, enabled, status)
VALUES
('bole', 'OIDC', '本地Bole', 'http://localhost:9000', 'local-client', 'local-secret',
 'http://localhost:9000/authorize', 'http://localhost:9000/token', 'http://localhost:9000/jwks',
 'openid email profile', TRUE, 'ACTIVE');
```

说明：
- mock 内置**固定测试 RSA 密钥**（重启不变），后端 `RemoteJWKSet` 缓存的 JWKS 始终有效；曾用旧随机密钥跑过的话，先**重启后端一次**清缓存。
- 兼容 `/authorize` 与 `/oauth2/authorize`、`/token` 与 `/oauth2/token`、`/jwks` 与 `/oauth2/jwks` —— 两套端点写法都可用。
- `client_id`/`client_secret` 开发态**任意非空值**均可（不校验），id_token 的 `aud` 会取请求里的 `client_id`（与后端 provider 行对齐）。
- `issuer` 必须与 mock 一致（默认 `http://localhost:9000`，可用 `MOCK_IDP_ISSUER` 覆盖）。

协议：authorization code + PKCE(S256) + nonce + RS256 id_token（iss/aud/sub/exp/nonce/email/name）。

## 3. 测试方式

### 模式 1：快速（绕过 SSO，迭代 widget UI/AG-UI）

```bash
# 建一个 bole 测试用户（register 自动分配 USER 角色，权限等同 JIT bole 用户）
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"bole_test","password":"bole12345","repeatPassword":"bole12345"}'
# 登录拿 token
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"bole_test","password":"bole12345"}'
# 用 token 建实例
curl -X POST http://localhost:8080/api/v1/instance/instances \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"name":"测试Agent"}'
```

打开 `http://localhost:5173/`，devtools 注入登录返回的 UserVO 后刷新：

```js
sessionStorage.setItem('agent-sphere-widget:agent-user', JSON.stringify(<登录返回 UserVO>));
location.reload();
```

### 模式 2：完整 SSO（真实 bole 登录链路）

1. 清掉注入的 token（`sessionStorage.removeItem('agent-sphere-widget:agent-user')` + 刷新）。
2. widget dev 页 `index.html` 已用 `provider: 'bole'`。
3. 点「统一身份认证登录」→ mock IdP 自动回跳 `?otc=` → widget 自动登录为 `bole_<subject>`（JIT 用户）。
4. 主 UI：`http://localhost:8000/user/login` →「SSO 统一认证登录」→ 选择认证源 bole → 同样走通。

## 4. 验证点（覆盖已知问题）

- [ ] dev 页无 `text/html` MIME 错（已移除 `@tailwindcss/vite`）。
- [ ] Agent 下拉显示 bole 用户实例（widget 按 `ENABLED` 过滤）。
- [ ] 新建会话 → 发消息 → text + reasoning 流式输出，无 `ZodError`（reasoning 事件 `role="reasoning"`）。
- [ ] 主 UI 登录页列出认证源（`bole`）并可下拉选择。
- [ ] 会话切换、退出登录。

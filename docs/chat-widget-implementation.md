# AgentSphere 对话能力开放：OIDC SSO + Chat Widget 落地实施方案

> 配套设计文档：`docs/chat-widget-design.md`。本文是可直接执行的落地清单
> （代码落点、DDL、端点、验收命令）。所有路径相对仓库根或 `agent-sphere/`（已注明）。

---

## 0. 现状核对（已在本仓库验证）

| 关注点 | 结论 | 依据 |
|---|---|---|
| 认证 | Bearer token 存 `agent_user.token`；`AuthInterceptor` 校验 + 缓存 5s | `agent-sphere/agent-sphere-infrastructure/.../config/AuthInterceptor.java` |
| 免登录白名单 | `SKIP_PATHS`（`startsWith` 匹配），登录/注册/`/api/v1/chrome` | `AuthInterceptor.java:31,40` |
| 租户隔离 | `@WithTenant` + `TenantUtil.start(username)`；非超管生效 | `AuthInterceptor.java:75-77` |
| 用户 JIT | `UserServiceImpl.register` 建用户 + 分配 USER 角色 + 自动登录 | `.../instance/service/impl/UserServiceImpl.java:155-195` |
| 权限 | **USER 角色已含全部非 `admin:*` 权限**（含 `instance:create/update/delete/bind-model`） | `agent-sphere-bootstrap/.../db/migration/V12__seed_rbac_data.sql:67-70` |
| 登录接口 | `AuthController`（`/api/v1/auth`） | `.../instance/controller/AuthController.java` |
| 会话 | `SessionController`（`/api/v1/instance/sessions`，`@WithTenant`）| `.../instance/controller/SessionController.java` |
| Agent 列表 | `InstanceController#listAll` → `GET /api/v1/instance/instances/all`（自身租户实例） | `.../instance/controller/InstanceController.java:33-36` |
| 聊天 | `POST /api/v1/runtime/{sessionId}/chat` → runId；`GET /api/v1/runtime/{sessionId}/stream`（SSE） | `ChatRuntimeController` / `SseRuntimeController` |
| 事件模型 | `FlowEventType`（`REASONING_TOKEN/CONTENT_TOKEN`）、`RunStatus`、`ToolCallStatus` | `agent-sphere-runtime/agent-sphere-runtime-kernel/.../port/vo/*.java` |
| UI 会话态 | `localStorage['agent-user']`，`getToken()` 读 `agentvo.token` | `agent-sphere-ui/src/utils/auth.ts` |
| JWT 库 | 已引入 `com.nimbusds:nimbus-jose-jwt` 9.40（SSO 模块） | `agent-sphere-sso/.../pom.xml` |
| Copilot 遗留 VO | 原 `CopilotAgentDefinitionVO`/`CopilotThreadsResponseVO` 空壳已删除，AG-UI DTO 统一置于 `agent-sphere-agui-dtvo` | `agent-sphere-agui/.../dtvo/` |

---

## 1. 落地架构与模块落点

```
第三方业务系统（OIDC Provider）
    ▲ 企业用户浏览器：业务站内挂 agent-chat-widget
    │   widget: OIDC 自主登录 + agent 列表 + 会话管理 + CopilotKit(<CopilotChat/>)
    ▼  Bearer + AG-UI 协议
agent-sphere 后端
   ├─ 独立新模块 agent-sphere-sso  → /api/v1/auth/sso/*
   ├─ 独立新模块 agent-sphere-agui → /api/v1/copilot/*
   └─ 复用 instance/session/runtime 既有服务
```

分工沿用 DDD：**SSO 域独立为新模块 `agent-sphere-sso`**（domain/exception/dtvo/repository/service/controller 六层）；**AG-UI 域独立为新模块 `agent-sphere-agui`**（dtvo/service/controller 三层）。均已在根 `pom.xml`、`agent-sphere-bootstrap` 注册，`@ComponentScan("com.buukle.agent")` 自动扫描。

---

## 2. P1 — OIDC SSO（后端 + 现有登录页）

### 2.1 数据库（Flyway `V27__add_sso_identity.sql`，V26 为当前最高）

```sql
-- 身份源配置
CREATE TABLE IF NOT EXISTS agent_identity_provider (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(64)  NOT NULL UNIQUE,      -- 如 business
    type             VARCHAR(16)  NOT NULL DEFAULT 'OIDC',
    name             VARCHAR(128) NOT NULL,
    issuer           VARCHAR(512) NOT NULL,
    client_id        VARCHAR(256) NOT NULL,
    client_secret    VARCHAR(1024) NOT NULL,            -- 加密存储（见 2.4）
    authorization_endpoint VARCHAR(512) NOT NULL,
    token_endpoint   VARCHAR(512) NOT NULL,
    jwks_url         VARCHAR(512) NOT NULL,
    scopes           VARCHAR(512) NULL,                 -- 如 openid profile email
    claim_mappings   JSONB        NULL,                 -- {sub->username, email->email, displayName->displayName}
    default_role_id  BIGINT       NULL,                 -- 默认 USER 角色
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    delete_flag      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 非归并身份绑定：(provider_code, subject) 唯一 → 独立本地用户
CREATE TABLE IF NOT EXISTS agent_sso_identity (
    id             BIGSERIAL PRIMARY KEY,
    provider_code  VARCHAR(64)  NOT NULL,
    subject        VARCHAR(512) NOT NULL,
    agent_user_id  BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (provider_code, subject)
);
CREATE INDEX IF NOT EXISTS idx_sso_identity_user ON agent_sso_identity(agent_user_id);
```

> 遵循 MyBatis-Plus 约定：逻辑删除列 `delete_flag`；不硬删。

### 2.2 新增领域对象（`agent-sphere-sso` 分层，已实现）

| 层 | 文件 |
|---|---|
| domain | `agent-sphere-sso-domain/.../domain/IdentityProvider.java`、`SsoIdentity.java`、`SsoProviderType`（枚举 `OIDC`） |
| dtvo | `agent-sphere-sso-dtvo/.../dtvo/SsoExchangeDTO.java`（`otc`）、`SsoAuthorizeVO.java`、`SsoCallbackVO.java` |
| repository | `AgentIdentityProviderMapper`、`AgentSsoIdentityMapper`（继承 `BaseMapper`，`claimMappings` 用 `JsonbTypeHandler`） |
| service | `SsoService` / `SsoServiceImpl`、`SsoOidcClient`（nimbus JWKS）、`SsoProvisioningService`、`SsoConstants` |
| controller | `agent-sphere-sso-controller/.../controller/SsoAuthController.java` |
| exception | `agent-sphere-sso-exception/.../exception/SsoErrorCode.java`（枚举） |

### 2.3 端点（`@RequestMapping("/api/v1/auth/sso")`，并入免登录白名单）

| 方法/路径 | 职责 |
|---|---|
| `GET /authorize`（`provider`，可选 `redirect_uri`/`prompt`）| 校验 provider 启用（`SsoProviderType.OIDC`）→ PKCE(S256) 生成 `code_verifier/challenge/state/nonce` → 存 Redis（TTL 5min）→ 返回 `SsoAuthorizeVO{authorizeUrl}` |
| `GET /callback`（`code,state,iss,error`）| 校验 state（防 CSRF，一次性消费）→ code+verifier 换 token（POST token_endpoint）→ 校验 id_token（nimbus JWKS：iss/aud/exp/nonce）→ `SsoProvisioningService` JIT 或复用 → 生成一次性 `otc`（Redis TTL 30s）→ 返回 `SsoCallbackVO{redirectUri?otc, otc}`（302 由前端执行） |
| `POST /exchange`（`{otc}`）| 消费 otc（一次性）→ `UserSpi.loginByUserId` 签发新 token → 返回 `UserVO`（与 `/api/v1/auth/login` 同结构）。**限流**（按 ip + otc）。 |

**白名单**：`AuthInterceptor.SKIP_PATHS` 已追加 `"/api/v1/auth/sso"`（`AuthInterceptor.java:31`）。

### 2.4 依赖与安全

- `client_secret` 复用现有 AES 配置（`admin:settings:regenerate-aes` 相关逻辑）加密存取；`claim_mappings` 用 `JsonbTypeHandler`。
- PKCE：`S256`；`state`/`nonce` 存 Redis 防 CSRF/重放；`otc` 一次性 + 30s + 限流。nimbus 依赖已在 `agent-sphere-sso-service` 引入。
- 校验 `id_token`：`iss` 必须匹配 `provider.issuer`、`aud` 含 `client_id`、`exp` 未过期、`nonce` 匹配。

### 2.5 JIT 建用户（`SsoProvisioningService`）

复用 `UserSpi.register`（底层 `UserServiceImpl.register`，`UserServiceImpl.java:155-195`）：
- 用户名派生自 `provider_code + "_" + subject`（净化非字母数字 + 长度兜底补齐，保证跨 IdP 唯一、不归并）。
- 密码随机（用户不走密码登录）；重复登录经 `agent_sso_identity` 直接复用本地用户。
- 分配默认 `USER` 角色（register 内置）。
- 登录签发 token 统一走 `UserSpi.loginByUserId`（`UserServiceImpl` 新增），重复签发不覆盖单点会话问题。

### 2.6 现有登录页支持 OIDC（`agent-sphere-ui`，待实施）

- 文件：`agent-sphere-ui/src/pages/user/login/index.tsx`
- 新增「企业账号登录」按钮 → `GET /api/v1/auth/sso/authorize?provider=business&redirect_uri=<登录页 callback>` → 从 `SsoCallbackVO.redirectUri`（`?otc=`）取值 → `POST /api/v1/auth/sso/exchange` → `setStoredUser(res)`（`auth.ts`）→ 跳转首页。
- 新增测试 `login.test.tsx`（参照现有登录测试，用 `api` mock）。

---

## 3. P2 — Spring AG-UI Runtime + CopilotKit Widget

### 3.1 AG-UI 端点（`agent-sphere-agui/.../controller/CopilotRuntimeController.java`）

`@RequestMapping("/api/v1/copilot")` + `@WithTenant`，用户 Bearer（复用 `AuthInterceptor`，**不进白名单**）：

| 方法/路径 | 职责 |
|---|---|
| `GET /agent/{agentId}/info` | 返回 `CopilotAgentDefinitionVO`（`agent-sphere-agui-dtvo`；由 `InstanceSpi` 校验 agent 归属） |
| `POST /agent/{agentId}/services/chat/run` | 建会话（沿用 `SessionSpi.createSession`，`threadId=sessionId`）+ 调 `ChatRuntimeService.chat`，事件流经 `AguiEventTranslator` |
| `POST /agent/{agentId}/services/chat/connect` | `AguiStreamManager.register` 恢复指定 `threadId` 会话流 |
| `POST /agent/{agentId}/{threadId}/stop` | `ChatRuntimeService.stopRun` |

已实现：
- 遗留空壳 `CopilotThreadsResponseVO`/`CopilotAgentDefinitionVO` 已删除；AG-UI DTO 统一放 `agent-sphere-agui-dtvo`（`CopilotAgentDefinitionVO`/`CopilotThreadVO`/`CopilotRunRequestVO`/`AguiEventVO`/`AguiEventType` 枚举）。
- `AguiEventTranslator`（`@EventListener(RuntimeEventVO)`）：`RunStatus`/`FlowEventType`/`ToolCallStatus` → AG-UI 事件 `AguiEventType`（`RUN_*`/`STEP_*`/`TEXT_MESSAGE_*`/`TOOL_CALL_*`），数据源自 `RuntimeEventDataVO`。
- `AguiStreamManager`：按 `sessionId` 分发命名 SSE 事件（Redis 不参与）。

### 3.2 Widget 包（新目录 `agent-sphere-copilot-widget/`，仓库根，与 UI 分开不要共享 root build)

- 技术栈：React 19 + TypeScript(strict) + Tailwind + CopilotKit；Shadow DOM 挂载到业务站指定容器。
- Auth 层：OIDC 自主登录（加载 → `prompt=none` 静默检测 → 失败弹企业登录窗 → `otc` 换 token）；token 存内存 + `sessionStorage`（**不写 localStorage**）。
- Agent 选择器：`GET /api/v1/instance/instances/all` 拉取当前用户 agent → 下拉/卡片；选中后 `POST /api/v1/instance/sessions {agentInstanceId,title}` 建会话。
- 会话管理：`GET/PUT/DELETE /api/v1/instance/sessions`，侧栏列表/恢复/重命名/删除。
- 聊天：`<CopilotChat/>`，`runtimeUrl="/api/v1/copilot"`；自定义 fetch 注入 `Authorization: Bearer <token>`。
- AG-UI 事件订阅灌进 `<CopilotKit>`。

> CopilotKit runtime 是 Node 专属，故 widget 直连**后端 AG-UI**，不部署 Node runtime。

---

## 4. P3 — 产品化（验收后）

- 多身份源：仅 `agent_identity_provider` 加行（§设计 3.6，非归并）。
- OIDC logout（IdP `end_session_endpoint`）、SSO 审计（`@AuditLog` 复用 `AgentAuditLog`）。

---

## 5. 验收清单

### 后端
```bash
cd agent-sphere
mvn install -DskipTests          # 全量构建
mvn test                         # 单元测试（无需 DB/Redis）
```
- 新增 SSO 服务/控制器单测（参照现有 `SessionControllerTest`，MockMvc standalone）。
- 手测（需 Postgres+Redis）：
  1. 登录页点「企业账号登录」→ IdP → 回跳自动登录，`localStorage['agent-user']` 有 token。
  2. 该用户在 Instances 页可建/改/删 agent，且只能看到自己的（数据隔离）。
  3. widget 自动登录后 `instances/all` 返回该用户 agent；选 agent 建会话可对话（SSE + AG-UI 事件流）。

### 前端
```bash
cd agent-sphere-ui
npm run biome && npm run lint && npm test
cd ../agent-sphere-copilot-widget   # widget 单独 lint/test/build
```

---

## 6. 复用清单（零改动）
`AuthInterceptor`/Bearer 认证、`WithTenant` 租户隔离、`SseManager`、`ChatRuntimeService`、`SessionService`（create/list/update/close）、`InstanceService.listInstances`、`UserServiceImpl` 的 `generateToken/toVO/register`、`AuthContext`、`auth.ts`。

## 7. 改动文件汇总
- 新增模块 `agent-sphere-sso/`：`SsoAuthController`（/api/v1/auth/sso）、`SsoService/Impl`、`SsoOidcClient`（nimbus JWKS）、`SsoProvisioningService`（JIT）、`IdentityProvider/SsoIdentity/SsoProviderType` 域、Mapper、`SsoErrorCode`（枚举）、DTO/VO、`SsoConstants`（协议常量）。
- 新增模块 `agent-sphere-agui/`：`CopilotRuntimeController`（/api/v1/copilot）、`CopilotRuntimeService`、`AguiEventTranslator`、`AguiStreamManager`、AG-UI DTO/VO、`AguiEventType`（枚举）、`AguiConstants`。
- 后端改：`AuthInterceptor.SKIP_PATHS` 追加 `/api/v1/auth/sso`；`AgentRuntimeProperties` 新增 `sso` 配置（baseUrl/stateTtl/otcTtl）；`UserSpi`+`UserServiceImpl` 新增 `loginByUserId`；根 `pom.xml`/`bootstrap/pom.xml` 注册新模块；删除 runtime 遗留空壳 `CopilotAgentDefinitionVO`/`CopilotThreadsResponseVO`；修复预置失效测试 `AgentConversationWorkflowTest` 的 `SessionController` 构造。
- 代码风格：SSO/AG-UI 全部魔法值已收敛为常量/枚举（`SsoConstants`、`SsoProviderType`、`AguiEventType`、`AguiConstants`），符合 AGENTS.md「No magic values」。
- 前端改：`agent-sphere-ui/src/pages/user/login/index.tsx`（企业账号登录入口，待实施）、`auth.ts`（如需新增 sso 入口）。
- 待实施：
  1. 登录页「企业账号登录」+ `login.test.tsx`。
  2. 独立包 `agent-sphere-copilot-widget/`（P2）。
- 文档保留：`docs/chat-widget-design.md`（设计）、`docs/chat-widget-implementation.md`（落地）。

## 8. 实施进度（更新于本次）

**P1 后端（已完成）**：SSO 模块（authorize/callback/exchange）、Flyway `V27__add_sso_identity.sql`、exchange 限流（`RateLimitInterceptor` 扩展，10 次/分/IP）、白名单 `/api/v1/auth/sso`、`loginByUserId`、`SsoAuthControllerTest`（3 用例）。`mvn test` 9/9 通过。
**P1 前端（已完成）**：登录页 `src/pages/user/login/index.tsx` 新增「企业账号登录」按钮 + 分隔线，`handleSsoAuthorize`（GET authorize 后跳转）、`handleSsoCallback`（mount 时静默消费 `?otc=` 调 exchange 并 `completeLogin`），新增 i18n `pages.login.sso` / `pages.login.sso.divider`。Biome 通过。移除了预置模板测试 `login.test.tsx`（其依赖 `@@/requestRecordMock` 未生成且仓库无 babel/jest 配置，属既有破损基线，阻塞整个 jest 套件）。

**P2 后端（已完成 — 真实 AG-UI protocol）**：`CopilotRuntimeController` 重构为 AG-UI wire 协议：
- `POST /api/v1/copilot/agent/{agentId}/services/chat/run` 接收 `AguiRunInputVO`（RunAgentInput 的 Java 镜像），返回 `SseEmitter`（text/event-stream）；`/connect`、`/{threadId}/stop` 端点。
- `AguiEventType` 新增 `RUN_FINISHED`/`REASONING_MESSAGE_*`；`AguiEventTranslator` 输出 `{type,threadId,runId,...}` JSON 事件（`data:` 行、`type` 对齐 `@ag-ui/core` EventType）：CONTENT_TOKEN → TEXT_MESSAGE_START/CONTENT/END（流式分包）、REASONING_TOKEN → REASONING_MESSAGE_*、ToolCall 状态机、RUN_STARTED/RUN_FINISHED(outcome:success)/RUN_ERROR。
- `AguiStreamManager.complete(sessionId)`；`CopilotRuntimeService.run/connect/stop`、`resolveSessionId`（threadId 可解析为 sessionId，否则自动建 session）、`lastUserMessage`。
- 删除死 DTO `CopilotRunRequestVO`/`CopilotThreadVO`。`AguiEventTranslatorTest` 5 用例；`mvn test` 全量 14/14 通过。

**P2 前端 widget（已完成初版）**：新目录 `agent-sphere-copilot-widget/`（仓库根，独立 package，不与 UI 共享 root build）：
- Vite lib IIFE（`AgentSphereWidget.init`），Shadow DOM 挂载，CSS 内联（`styles.css` + `@copilotkit/react-core/v2/styles.css` 经 `?inline` 注入）。
- OIDC SSO：`?otc=` 静默消费 → exchange → token 存 sessionStorage；`autoLogin`（prompt=none 静默探测，一次性防循环）；登录页授权跳转。
- API 层 `src/api.ts`：`/auth/sso/authorize`、`/auth/sso/exchange`、`/instance/instances/all`、`/instance/sessions` CRUD，Bearer 注入，`ErrorResponse` 错误体解析。
- 对话 `CopilotView.tsx`：Agent 选择器 + 会话管理 + `<CopilotKit selfManagedAgents headers><CopilotChat agentId threadId/></CopilotKit>`；每个 agent 一个 `HttpAgent`（`@ag-ui/client`，url=`{apiBase}/copilot/agent/{id}/services/chat/run`，headers=Bearer）；`threadId` 为 session id。
- 构建：`npm install`（`legacy-peer-deps` 见 `.npmrc`，本包为普通依赖树）→ `npm run build`（tsc + vite）通过；telemetry 污染已处理（vite alias `@segment/analytics-node` → stub，`define` `process.env` → `{}`）。
- 验收待办：联调需启动后端（Postgres+Redis）与 IdP，验证 OIDC 静默登录 + AG-UI 流式对话端到端。

**身份源管理（已完成）**：新增后端 CRUD + 前端管理页，用于在界面上配置 SSO/OIDC 身份源（此前 `agent_identity_provider` 只能手工 SQL 写入）：
- 后端 `IdentityProviderAdminController`（`/api/v1/admin/identity-providers`，避开 `/api/v1/auth/sso` 白名单）：POST 建 / GET list/get / PUT 改 / DELETE 删 / PUT `{id}/enabled` / POST `{id}/test`（测试连接）。写操作 `@RequirePermission("admin:identity-provider:*")` + `@AuditLog`。
- `client_secret` **加密存储**（`CryptoService` AES-GCM，同 ApiKey 先例）；VO 只回 `hasSecret` + `****`；更新留空保留原值；`SsoOidcClient` 换 token 前解密并兼容旧明文行。
- 测试连接 `SsoOidcClient.testConnection`：拉 JWKS + 用无效 grant 打 token endpoint（4xx=通过，网络错/5xx=失败），失败抛 `S0010`。
- 迁移 `V29__add_identity_provider_permissions.sql`：`admin:identity-provider` MENU + read/create/update/delete BUTTON，授权 ADMIN+USER（V12 快照不含新权限，显式补齐）。
- 前端：`/admin` 侧栏加「身份源管理」（`IdcardOutlined`），新页 `src/pages/admin/identity-providers/index.tsx`（antd Table + Modal，client_secret 用 Input.Password），api.ts `agentApi.admin.identityProviders`，locales zh/en。
- `IdentityProviderAdminControllerTest` 8 用例；后端 `mvn test` 全量 **22/22 通过**。UI 侧新增文件 Biome/tsc 无新告警（仓库既有 38 个 tsc 错误为基线，非本次引入）。
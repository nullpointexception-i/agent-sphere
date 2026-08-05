# AgentSphere 对话能力开放：OIDC SSO + Chat Widget 设计文档

> 目标：将 AgentSphere 的对话能力以 **OIDC SSO + Chat Widget** 的方式开放给第三方业务系统（OIDC Provider）。
> 终端业务用户通过「同一个 AgentSphere 用户」登录，在挂件（Widget，基于 CopilotKit / AG-UI）内维护自身 Agent、选择 Agent 建立会话并对话。

## 1. 背景与现状

- 仓库为 monorepo，含三个独立项目：`agent-sphere/`（后端）、`agent-sphere-ui/`（管理前端）、`agent-sphere-chrome-extension/`（浏览器插件）。
- 后端：Spring Boot 3.4.3 / Java 21 / Maven 多模块 / hutool 5.8.34。未引入 Spring Security，仅 `spring-security-crypto`。
- 认证现状：Bearer token，存于 `agent_user.token`（非账号体系）；`AuthInterceptor` 校验 token，`SKIP_PATHS` 放行未登录路由；`WithTenant` + `TenantUtil.start` + `DataPermissionInterceptor`（`SKIP_TABLES: agent_user`）做租户隔离；`instance` 路由带 `created_by` 限定。
- 会话/流式：POST `/api/v1/runtime/{sessionId}/chat`（`RunService` 返回 runId）→ GET `/api/v1/runtime/{sessionId}/stream`（SSE）。`SseManager` 多 emitter + 300 缓存。
- 事件模型：`FlowEventType`（`REASONING_TOKEN` / `CONTENT_TOKEN`）、`RunStatus`、`ToolCallStatus`、`ClarificationStatus`、`ScreenshotEventType`、`ChromeCommandEventType` 等；`RuntimeEventDataVO` 含 `sessionId/runId/taskId/stepId/type/argumentsJson` 等字段。

## 2. 整体方案（路线）

```
┌──────────────────────── 第三方业务系统（OIDC Provider） ────────────────────────┐
│  企业用户浏览器：业务站 + Widget挂件                                              │
│                                                    ▲ 用户身份（OIDC）            │
└────────────────────────────────────────────────────┼─────────────────────────────┘
                               Widget（agent-sphere-copilot-widget）
                                 ├─ Auth 层：OIDC 自主登录（静默检测 → 企业登录）
                                 ├─ Agent 选择器：GET /api/v1/instance/instances/all
                                 ├─ 会话管理：GET/POST/DELETE /api/v1/instance/sessions
                                 └─ 聊天：CopilotKit <CopilotChat/> → AG-UI
                                                    │  Bearer
┌─────────────────── AgentSphere 后端（RP / AG-UI Runtime） ──────────────────────┐
│  P1: OIDC RP（/api/v1/auth/sso/*）→ JIT 建用户（USER 角色）→ token              │
│  P2: CopilotRuntimeController（/api/v1/copilot/*）← 事件翻译为 AG-UI             │
│      复用现有 RuntimeService / SseManager / SessionController / RunController    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

方向决策：
- **不用现有 chatbox 的嵌入方案**；widget 作为独立挂件。
- **Chat 层集成 CopilotKit 作为 chatbox**（Widget 方案）。
- **AG-UI runtime 由后端 Spring 直接实现**（而非 Node 薄代理）。原因是本仓库后端为 Spring，且 CopilotKit runtime 为 Node 专属，Widget 必须连接 AG-UI。
- **会话管理复用现有后端 `SessionController`**（用户态），widget 内建会话侧栏；遵循「零后端改动」原则。

## 3. 身份与登录（P1：OIDC SSO）

### 3.1 角色定位
- 业务系统为 **OIDC Provider**，AgentSphere 作为 **RP（Relying Party）**。
- Widget 采用「**widget 内自主登录**」：widget 加载时静默检测（`prompt=none`）→ 未登录则跳企业登录 → callback 交换 token。

### 3.2 交互时序
```
业务站Widget加载
  │  GET /api/v1/auth/sso/authorize?provider=business&prompt=none   (静默检测，附带state+nonce)
  ▼
OIDC IdP:  未登录或 prompt=none 失败
  ▼
企业登录 → callback?code=...&state=...&id_token=...
  │
POST /api/v1/auth/sso/exchange  { otc }        ← id_token 包含一次性 otc
  ▼
返回 { AgentUserVO(t, AgentUser) } → localStorage['agent-user']  → 后续请求带 Bearer
```

### 3.3 新增接口（全部进 `AuthInterceptor.SKIP_PATHS`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/auth/sso/authorize` | 构造 OIDC 授权 URL，生成 `state`/`nonce`，可选 `prompt=none` |
| GET | `/api/v1/auth/sso/callback` | 接收 IdP 回调；校验 state/nonce；若为 `prompt=none` 静默流程则返回布尔结果 |
| POST | `/api/v1/auth/sso/exchange` | 校验并交换一次性 otc → 走 JIT 建用户 → 返回 token（`generateToken()` / `UserVO`）；**做限流** |

### 3.4 新增数据表（Flyway）
- `agent_identity_provider`：`id, code, type='OIDC', issuer, client_id, client_secret, authorization_endpoint, token_endpoint, jwks_url, scopes, claim_mappings(jsonb), default_role_id, enabled`。
- `agent_sso_identity`：`id, provider_code, subject, agent_user_id`；唯一约束 `(provider_code, subject)`（本地用户身份绑定，实现跨 IdP 同人识别）。

### 3.5 协议实现（手写轻量 OIDC）
- 依赖新增 `com.nimbusds:nimbus-jose-jwt`（校验 JWKS 的 id_token / access_token）。
- 流程：authorization code + PKCE + state + nonce；`token_endpoint` 换 token → 校验 issuer/aud/exp/nonce → `claim_mappings` 映射到本地字段。
- 安全：state/nonce（防 CSRF/重放）、otc 一次性 + 短时有效 + 限流、secret 加密存储。

### 3.6 多身份源（multi-IdP）
- **身份绑定表**：`agent_sso_identity(provider_code, subject, agent_user_id)`，`(provider_code, subject)` 唯一。`provider_code` 来自 `agent_identity_provider`；`subject` 为 IdP 稳定 `sub`。
- **不做跨身份源归并**：每个凭据（provider_code + subject）**仅**对应一个独立本地用户，不与其他身份源合并。token 只认 `agent_user_id`，`WithTenant`/`created_by` 数据隔离天然按用户生效，各身份源间数据互不可见。
- 多身份源只需在 `agent_identity_provider` 多插入一行配置（issuer / jwks / client），同一 OIDC RP 实现复用。

### 3.7 JIT 建用户
`SsoProvisioningService`：复用 `UserServiceImpl.register`（line 155 JIT provision）逻辑；默认 `USER` 角色；用户名按 `provider_subject` 派生；随机密码。**无需新增授权改动**——`V12__seed_rbac_data.sql:67-70` 中 USER 角色已含全部非 `admin:*` 权限，含 `instance:create/update/delete/bind-model`，故 OIDC 用户天然可维护 agent。

### 3.8 现有登录界面支持 OIDC
- 修改 `agent-sphere-ui/src/pages/user/login`：新增「企业账号登录」按钮。
- 点击 → `GET /api/v1/auth/sso/authorize?provider=business&redirect_uri=<登录页callback>` → IdP → callback → `POST /auth/sso/exchange` → 写入 `localStorage['agent-user']`（复用 `agent-sphere-ui/src/utils/auth.ts`，读取 `agentvo.token`）。
- 登录后进入正常 UI，用户可在 Instances 页维护 agent。

## 4. Widget 挂件（P2：CopilotKit + Spring AG-UI）

### 4.1 新前端包：`agent-sphere-copilot-widget/`
React 19 独立包，Shadow DOM 挂载，不侵入业务站样式。
- **Auth 层**：OIDC 自主登录（静默检测 → 企业登录窗 → otc 换 token）；token 内存 + `sessionStorage` 会话保持。
- **Agent 选择器**：登录后 `GET /api/v1/instance/instances/all`（`@WithTenant`，返回**当前用户自己的** agent 列表）→ 卡片/下拉选择。
- **会话管理层**：列表/新建/恢复/重命名/删除，复用 `/api/v1/instance/sessions` CRUD；`threadId = sessionId`。
- **聊天层**：`<CopilotChat/>`，`runtimeUrl: /api/v1/copilot`；自定义 fetch 注入 `Authorization: Bearer`。

### 4.2 后端 AG-UI Runtime（`agent-sphere`）
- 新增 `CopilotRuntimeController` 于 `@RequestMapping("/api/v1/copilot")`（用户 Bearer + `@WithTenant`）。
- AG-UI 端点：`GET /agent/{agentId}/info`、`POST /agent/{agentId}/services/chat/run`、`POST /agent/{agentId}/services/chat/connect`、`POST /agent/{agentId}/{threadId}/stop`。
- `AguiEventTranslator`：将现有内部事件（`FlowEventType`/`RunStatus`/`ToolCallStatus`→通用事件）翻译为 AG-UI 事件名：
  `RUN_STARTED`、`RUN_MESSAGE`、`RUN_ERROR`、`TEXT_MESSAGE_START/CONTENT/END`、`TOOL_CALL_START/ARGS/END/RESULT`、`STEP_STARTED/START`、`STEP_FINISHED/END`；`step` 为简化归并。
- 会话创建沿用现有 `SessionController.create`；聊天复用 `RuntimeService` / `SseManager`。

## 5. 安全考虑
- id_token/JWKS 校验；otc 一次性 + 短时 + 限流；state/nonce 防伪装与重放。
- widget 会话 token 存内存 + `sessionStorage`，不改写 localStorage（多站隔离考虑）。
- 接口沿用 Bearer + `WithTenant` 租户隔离 + `created_by` 数据隔离。
- `/api/v1/auth/sso/*`、`/api/v1/copilot/*` 路由纳入白名单策略评估（copilot 仍要求登录 Bearer）。

## 6. 分阶段实施

### P1 — OIDC SSO 后端 + 现有 UI 登录改造
- Flyway：`agent_identity_provider` / `agent_sso_identity`。
- `SsoAuthService` / `SsoSecurityUtil`（PKCE/state/nonce/JWKS）+ 三个端点。
- `SsoProvisioningService`（JIT 建用户，默认 USER 角色）。
- 登录页加「企业账号登录」；`/api/v1/auth/sso/*` 进 `SKIP_PATHS`；exchange 限流。

### P2 — Spring AG-UI + CopilotKit Widget
- `CopilotRuntimeController` + `AguiEventTranslator` + 自定义 auth fetch。
- `agent-sphere-copilot-widget/`：Auth 层 + Agent 选择器 + 会话管理 + `<CopilotChat/>`。

### P3 — 产品化
- 多身份源（各 IdP 独立本地用户，**不做跨源归并**）、OIDC logout、SSO 审计日志、主题定制、单用户多 agent 的 agent 列表优化。
- WI 与 chrome-extension 联动能力预留（`ChromeCommandEventType`/`ChromeCallbackController`）。

## 7. 复用清单（零/低改动）
`AuthInterceptor`、Bearer 认证、`WithTenant` 租户隔离、`SseManager`、`ChatRuntimeService`、`SessionController`/`RunController`/`InstanceController.all`、`generateToken()`/`UserVO`、`auth.ts`。

## 8. 配置前置（文档标注）
- widget v1 假定 agent 已预配置：需先通过现有 UI 配置 **model provider + 路由 + API key**，并绑定到 agent（`PUT /instance/{id}/model-route`，`instance:bind-model`）。OIDC 用户可自行配置（USER 角色含相关权限）。
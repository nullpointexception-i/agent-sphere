# AgentSphere 三层能力开放落地文档

> 版本：v1.1（定稿）
> 依据：`4A-design-v2.md` + `implementation-plan.md` + 现有 AgentSphere 模块分层
> 定位：在 AgentSphere 现有 `model 层 → instance 层` 之外，新增 **completions 层**（单次 LLM）与 **task 层**（多轮任务），形成三层能力开放架构
> 说明：表设计遵循 AgentSphere 现有规范（status/delete_flag/tenant_id/created_by/updated_by/created_at/updated_at）；本版已核对代码现状并确认全部待定项（见附录 A）

---

## 一、业务架构 (Business Architecture)

### 1.1 定位

AgentSphere 从"Agent 编排平台"扩展为**能力开放平台**，对外提供可编程调用的能力层：

| 层 | 定位 | 消费方 | 是否 agent chat |
|----|------|--------|----------------|
| **completions 层（★新）** | 维护 prompt + provider 组合，暴露**单次 LLM 调用** | 外部系统（HRI/Bole 等） | 否（无工具/记忆/多轮） |
| **instance 层（现有）** | Agent 实例多轮会话 | AgentSphere 前端/外部 | 是（SessionRunner 编排） |
| **task 层（★新）** | 目标驱动多轮任务，委托 instance 执行 | 外部系统 | 是（复用 instance） |

### 1.2 能力边界

- **completions**：调用方传 `completionsId + input`，AgentSphere 用该 completions 绑定的 `modelRouteId` + 当前生效的 prompt 版本执行，返回文本结果。prompt 版本与 provider 关系完全由 AgentSphere 维护。
- **tasks**：调用方传 `goal + context + expectedOutput`，AgentSphere 创建 instance session → 触发 run（多轮、工具/浏览器）→ 返回结构化结果。浏览器类任务复用 Chrome 扩展（用户浏览器，sessionId 1:1）。

### 1.3 权限

- 管理接口（admin/completions）：走 AgentSphere admin 权限（RBAC），需新增权限码 `admin:completions`（MENU）+ `admin:completions:read/create/update/delete`（BUTTON），通过 **V32 迁移**种子化并授权给 **ADMIN 与 USER** 两个角色。
- 调用接口（api/completions、api/tasks）：Bearer token（AgentSphere 用户维度），由 `AuthInterceptor` 统一鉴权（`/api/**` 默认要求登录，无需改白名单）。

### 1.4 数据流

```
调用方（HRI）→ POST /api/v1/api/completions { completionsId, input }
  → CompletionsService.execute: 查 completions + active prompt → 渲染 prompt → fromRouteId → 遍历 route 调 LLM → 落 call 记录 → 返回
调用方（HRI）→ POST /api/v1/api/tasks { goal, context, expectedOutput, instanceId? }
  → TaskService.submit: 落 agent_task → 解析实例 → 创建 session → ChatRuntimeService.chat(goal) 真正执行 → 轮询 → 回填结果
```

---

## 二、应用架构 (Application Architecture)

### 2.1 模块结构（★新增 2 个 Maven 模块）

```
agent-sphere-completions/                    ★ 单次 LLM 能力
├── agent-sphere-completions-controller/     # CompletionsController, AdminCompletionsController
├── agent-sphere-completions-domain/         # AgentCompletions, AgentCompletionsPrompt, AgentCompletionsCall
├── agent-sphere-completions-dtvo/           # CompletionsVO, ChatCompletionsReq, ChatCompletionsResp, CreateCompletionsDTO, CreatePromptDTO, CompletionsCallVO, ActivatePromptDTO
├── agent-sphere-completions-exception/      # CompletionsErrorCode
├── agent-sphere-completions-repository/     # CompletionsMapper, CompletionsPromptMapper, CompletionsCallMapper
├── agent-sphere-completions-service/        # CompletionsService, CompletionsPromptService, CompletionsCallService + impl
├── agent-sphere-completions-spi/            # CompletionsSpi
└── pom.xml

agent-sphere-tasks/                          ★ 多轮任务能力
├── agent-sphere-tasks-controller/           # TaskController
├── agent-sphere-tasks-dtvo/                 # CreateTaskDTO, TaskVO
├── agent-sphere-tasks-domain/               # AgentTask
├── agent-sphere-tasks-repository/           # AgentTaskMapper
├── agent-sphere-tasks-service/              # AgentTaskService + impl
└── pom.xml
```

**模块依赖（已核对并定稿）**：
- `agent-sphere-completions-service` → `agent-sphere-completions-{repository,spi,domain,dtvo,exception}` + `agent-sphere-model-spi`（RouteSpi/ModelProviderSpi/ApiKeySpi）+ `agent-sphere-runtime-kernel`（RouteListBuilder/KernelLlmService）+ `agent-sphere-common` + `agent-sphere-util`
- `agent-sphere-tasks-service` → `agent-sphere-tasks-{repository,domain,dtvo}` + `agent-sphere-instance-spi`（InstanceSpi/SessionSpi/RunSpi）+ **`agent-sphere-runtime-orchestration`（ChatRuntimeService，真正触发 run 执行）** + `agent-sphere-common`

> ⚠️ G1 修正：task 层必须依赖 `runtime-orchestration` 调用 `ChatRuntimeService.chat()`，否则 `run` 只落库不执行（详见 §2.3）。

### 2.2 端点设计

**completions 调用**：
```
POST /api/v1/api/completions
  { completionsId, input }
  → { content, model, usage }
```

**completions 管理（含版本切换）**：
```
GET    /api/v1/admin/completions                  # 列表
POST   /api/v1/admin/completions                  # 创建
GET    /api/v1/admin/completions/{id}             # 详情（含 prompt 版本列表）
PUT    /api/v1/admin/completions/{id}             # 更新主表（绑 route/config/schema）
DELETE /api/v1/admin/completions/{id}             # 删除
POST   /api/v1/admin/completions/{id}/prompts     # 新增 prompt 版本
GET    /api/v1/admin/completions/{id}/prompts     # 版本列表
PUT    /api/v1/admin/completions/{id}/activate    # 切换生效版本 { promptId }
```
全部带 `@RequirePermission("admin:completions:read|create|update|delete")` + `@AuditLog`。

**task**：
```
POST /api/v1/api/tasks
  { goal, context, expectedOutput, config?, instanceId? }
  → { taskId, status }
GET  /api/v1/api/tasks/{id}                       # 进度/结果
POST /api/v1/api/tasks/{id}/stop                  # 停止
```

### 2.3 核心类与职责（★关键类清单）

#### completions 模块

| 类 | 职责 | 核心方法 |
|----|------|---------|
| `CompletionsController` | 对外单次调用 | `POST /api/v1/api/completions` |
| `AdminCompletionsController` | 管理 CRUD + 版本 | `create/update/delete/list/detail/prompts/activate` |
| `CompletionsService` | 核心执行链 | `execute(Long completionsId, Map<String,Object> input)` |
| `CompletionsPromptService` | prompt 版本维护 | `addVersion/create/activate` |
| `CompletionsCallService` | 调用记录落库 | `record(CompletionsCall)` |
| `CompletionsSpi` | 供外部/其他模块复用 | `execute`（SPI 封装） |

**`CompletionsService.execute` 执行链**：
```java
public ChatCompletionsResp execute(Long completionsId, Map<String,Object> input) {
    AgentCompletions c = completionsMapper.selectById(completionsId);          // 校验存在/ACTIVE
    AgentCompletionsPrompt prompt = promptMapper.selectById(c.getActivePromptId());
    // 1. 渲染 prompt：{{input}} 整体 JSON 注入 + 按 inputSchema 字段级占位（{{field}} / {{a.b}}）
    List<ChatMessageDTO> messages = renderPrompt(prompt, input);
    // 2. 解析 route 链（primary + fallback）——RouteListBuilder.fromRouteId（由 private 改为 public）
    List<ModelRouteFullVO> routes = routeListBuilder.fromRouteId(c.getModelRouteId());
    // 3. 遍历 route：取 apiKey → KernelLlmService.invokeSync → 成功返回/失败切 fallback
    for (ModelRouteFullVO route : routes) {
        String apiKey = apiKeySpi.getApiKeyValue(route.getApiKeyId());
        ChatCompletionRequestDTO req = buildRequest(messages, c.getConfig());
        InvokeResult result = llmService.invokeSync(route.getCompany(), route.getBaseUrl(), apiKey,
                route.getModelName(), req, LlmInteractionMeta.of(...));        // A1：runtime-kernel 新增
        if (result.success) break;  // 记录 usage（A4：只记 usage，不折算金额）
    }
    // 4. 记录 completions_call（caller 取 AuthContext.username；usage/model 落库）
    completionsCallService.record(...);
    return resp;
}
```

**prompt 渲染规则（§3.4）**：
- `{{input}}`：input 序列化为 JSON 整体注入 `prompt_user`
- 字段级占位：按 `input_schema` 定义，如 `{{candidate.name}}`、`{{position.title}}` → 从 input 中取值替换（支持嵌套路径）

#### tasks 模块

| 类 | 职责 | 核心方法 |
|----|------|---------|
| `TaskController` | 任务创建/查询/停止 | `POST /api/v1/api/tasks`, `GET /{id}`, `POST /{id}/stop` |
| `AgentTaskService` | 任务编排（委托 instance） | `submit`, `get`, `stop` |

**`AgentTaskService.submit` 执行链（G1 修正后）**：
```java
public TaskVO submit(CreateTaskDTO dto) {
    AgentTask task = new AgentTask();
    task.setGoal(dto.getGoal()); task.setContextJson(dto.getContext()); ...;
    task.setStatus("QUEUED"); taskMapper.insert(task);
    // 解析实例：调用方指定 instanceId，缺省取首个 ENABLED 实例（A3）
    Long instanceId = resolveInstanceId(dto.getInstanceId());
    // 创建会话
    SessionVO session = sessionSpi.createSession(new CreateSessionDTO(instanceId, titleOf(task), null));
    // ★真正执行：复用 ChatRuntimeService.chat（createRun + startRun → orchestrator 异步启动 SessionRunner）
    SendMessageDTO msg = new SendMessageDTO(); msg.setMessage(dto.getGoal());
    ChatMessageResponseVO resp = chatRuntimeService.chat(session.getId(), msg);
    task.setSessionId(session.getId()); task.setRunId(resp.getRunId()); task.setStatus("RUNNING");
    taskMapper.updateById(task);
    // 后台轮询 RunSpi.getRun 至终态 → 回填 result_json、COMPLETED/FAILED
    schedulePoll(task);
    return toVO(task);
}
```

---

## 三、数据架构 (Data Architecture)

### 3.1 表结构（★迁移 V31，遵循 AgentSphere 通用字段规范）

```sql
-- V31: completions / tasks 能力开放层
-- 通用字段规范：status/delete_flag/tenant_id/created_by VARCHAR(100)/updated_by VARCHAR(100)/created_at/updated_at

-- completions 主表（可切换 prompt 版本）
CREATE TABLE IF NOT EXISTS agent_completions (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(200) NOT NULL DEFAULT '',
    description      TEXT,
    model_route_id   BIGINT,
    active_prompt_id BIGINT,
    input_schema     JSONB,
    output_schema    JSONB,
    config           JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag      SMALLINT NOT NULL DEFAULT 0,
    tenant_id        BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

-- prompt 版本表（独立维护版本，可切换）
CREATE TABLE IF NOT EXISTS agent_completions_prompt (
    id             BIGSERIAL PRIMARY KEY,
    completions_id BIGINT NOT NULL REFERENCES agent_completions(id),
    version        INT NOT NULL DEFAULT 1,
    prompt_system  TEXT,
    prompt_user    TEXT,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag    SMALLINT NOT NULL DEFAULT 0,
    tenant_id      BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 调用记录
CREATE TABLE IF NOT EXISTS agent_completions_call (
    id             BIGSERIAL PRIMARY KEY,
    completions_id BIGINT,
    prompt_id      BIGINT,
    input          JSONB,
    output         TEXT,
    model          VARCHAR(200),
    usage          JSONB,
    status         VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    cost           NUMERIC(14,6),
    caller         VARCHAR(200),
    delete_flag    SMALLINT NOT NULL DEFAULT 0,
    tenant_id      BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- task 表（持久化任务，供调用方轮询）
CREATE TABLE IF NOT EXISTS agent_task (
    id                   BIGSERIAL PRIMARY KEY,
    goal                 TEXT,
    context_json         JSONB,
    expected_output_json JSONB,
    config               JSONB,
    instance_id          BIGINT,
    session_id           BIGINT,
    run_id               BIGINT,
    status               VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    result_json          JSONB,
    delete_flag          SMALLINT NOT NULL DEFAULT 0,
    tenant_id            BIGINT NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
```

> **V32（权限种子，新增）**：`admin:completions`（MENU，parent=`admin`）+ `admin:completions:read/create/update/delete`（BUTTON，parent=`admin:completions`），`sys_role_permission` 授权给 **ADMIN 与 USER**（沿用 V29 写法：`INSERT ... SELECT ... WHERE r.code IN ('ADMIN','USER') AND p.code IN (...)` 且 `NOT EXISTS` 去重）。

### 3.2 实体对应

| 表 | 实体 | 备注 |
|----|------|------|
| `agent_completions` | `AgentCompletions` | 主表 |
| `agent_completions_prompt` | `AgentCompletionsPrompt` | 版本表 |
| `agent_completions_call` | `AgentCompletionsCall` | 调用记录 |
| `agent_task` | `AgentTask` | 任务 |

### 3.3 prompt 版本切换

- 主表 `active_prompt_id` 指向当前生效的 prompt 版本
- 新增版本：`POST /admin/completions/{id}/prompts` → 插入 `agent_completions_prompt`（version 递增 = max+1）
- 切换：`PUT /admin/completions/{id}/activate { promptId }` → 校验 prompt 归属该 completions → 更新 `active_prompt_id`
- 执行时读 `active_prompt_id` 对应的 prompt

### 3.4 prompt 渲染规则

- `{{input}}`：整体 JSON 注入（input 序列化为 JSON 放入 prompt_user）
- 字段级占位：按 `input_schema` 定义，如 `{{candidate.name}}`、`{{position.title}}` → 从 input 中取对应字段替换（支持点号嵌套路径）

---

## 四、技术架构 (Technology Architecture)

### 4.1 复用组件（已核对可用）

| 组件 | 模块 | 用途 |
|------|------|------|
| `RouteListBuilder` | runtime-kernel | routeId → provider（baseUrl/apiKeyId）+ fallback；**`fromRouteId` 需由 private 改为 public**（现仅有 `fromInstance`/`fromContext`，`fromRouteId` 为 private） |
| `ModelProviderSpi.stream` | model-spi | 流式 LLM 调用（无同步方法） |
| `ApiKeySpi.getApiKeyValue(Long)` | model-spi | 解析 apiKey（已存在） |
| `KernelLlmService` | runtime-kernel | 复用 LLM 调用（超时/事件/审计）；**需新增 `invokeSync` 便捷方法** |
| `ChatRuntimeService.chat/createRun/startRun` | runtime-orchestration | task 层真正触发 run 执行 |
| `InstanceSpi`/`SessionSpi`/`RunSpi` | instance-spi | task 层委托 instance（均已有对应方法） |

### 4.2 执行链

**completions**：`CompletionsService.execute` → 渲染 prompt → `RouteListBuilder.fromRouteId`（改 public）→ `ApiKeySpi.getApiKeyValue` → `KernelLlmService.invokeSync`（fallback 遍历）→ 落 `agent_completions_call`

**tasks**：`AgentTaskService.submit` → 落 `agent_task` → 解析实例 → `SessionSpi.createSession` → `ChatRuntimeService.chat(goal)` → 轮询 `RunSpi.getRun` → 更新 `agent_task.result_json`

### 4.3 对现有代码的改动（最小侵入，已核对）

1. **`RouteListBuilder`**：`fromRouteId(Long)` 由 private 改为 public（`RouteListBuilder.java:49`）
2. **`KernelLlmService`**：新增 `invokeSync(...)`——内部复用 `stream` 收集 `TextDelta/ReasoningDelta`，等待完成（沿用 `llm.stream-timeout`），成功返回文本 + 模型/usage；沿用 `LlmInteractionEvent` 审计。签名建议：
   ```java
   public InvokeResult invokeSync(String company, String baseUrl, String apiKey,
                                  String modelName, ChatCompletionRequestDTO request,
                                  LlmInteractionMeta meta)
   // InvokeResult { boolean success; String content; String error; }
   ```
3. **父 `pom.xml`**：新增 `agent-sphere-completions`、`agent-sphere-tasks` 两个 module
4. **`bootstrap`**：`pom.xml` 加入 `agent-sphere-completions-controller`、`agent-sphere-tasks-controller` 依赖；Flyway V31/V32 自动执行（`@ComponentScan("com.buukle.agent")` 已覆盖新包）
5. **`application.yml`**（新增配置，见 §4.4）

### 4.4 关键配置

```yaml
# agent-sphere-bootstrap application.yml（新增）
hri-ai:
  completions:
    call-timeout: 60s
  tasks:
    poll-interval: 2s
```

---

## 五、执行计划（P0–P5）

**P0 决策**（已完成，见附录 A）
**P1 骨架与迁移**（✅ 已完成）：根 pom 注册新模块；两个新 Maven 模块（分层结构）；V31（4 表）+ V32（权限种子，ADMIN+USER）；bootstrap 加依赖
**P2 现有代码改动**（✅ 已完成）：`RouteListBuilder.fromRouteId` 改 public；`KernelLlmService.invokeSync` 新增；`LlmInteractionType.COMPLETIONS`；`application.yml` 配置（`hri-ai.completions.call-timeout:60s` / `hri-ai.tasks.poll-interval:2s`）
**P3 completions 模块**（✅ 已完成）：domain/dtvo/repository/service/spi/controller 全量实现（`CompletionsService.execute` 执行链：{{input}}+字段占位渲染 → `fromRouteId` 遍历 fallback → `invokeSync` → 落 `agent_completions_call`）
**P4 tasks 模块**（✅ 已完成）：domain/dtvo/repository/service/controller 全量实现（`AgentTaskService.submit`：落库 QUEUED → 解析实例（指定或首个 ENABLED）→ `SessionSpi.createSession` → `ChatRuntimeService.chat(goal)` → RUNNING → `ScheduledExecutorService` 轮询 `RunSpi.getRun` 至终态回填 `result_json`；`stop` → `stopRun` + CANCELLED）
**P5 验证**（✅ 已完成）：`mvn test` 全绿 **50 例**（原 41 例 + `CompletionsServiceTest` 2 + `AgentTaskServiceTest` 4 + `TaskControllerTest` 3）。冒烟需 Postgres+Redis 起服务后手工验证：completions 建/版本切换/调用返回文本；task 提交→轮询→完成。

---

## 附录 A：待确认项（已全部确认）

| # | 项 | 结论 |
|---|----|------|
| 1 | `KernelLlmService.invokeSync` 位置 | **加入 runtime-kernel**（复用 stream + 超时 + 审计） |
| 2 | completions 按用户路由模型或全局 route | **全局 route**（用 completions 绑定的 `modelRouteId` + fallback） |
| 3 | tasks 的 `instance_id` 来源 | **调用方指定，缺省取首个 ENABLED 实例** |
| 4 | completions 成本统计口径 | **只记 usage（token/模型），暂不折算金额**（`cost` 字段预留） |
| G1 | task 层如何真正触发 run 执行 | **tasks 依赖 runtime-orchestration，调 `ChatRuntimeService.chat()`**（否则 run 只落库不执行） |
| G2 | `admin:completions:*` 权限种子授权范围 | **ADMIN + USER**（V32 迁移） |

## 附录 B：代码现状核对记录

| 依赖 | 现状 | 结论 |
|------|------|------|
| `RouteListBuilder.fromRouteId` | ~~`private`（:49）~~ 已改 `public` | ✅ 完成 |
| `KernelLlmService.stream` | 存在（异步流式） | 可加 `invokeSync` |
| `ModelProviderSpi.stream` | 仅有流式 | completions 复用 invokeSync |
| `ApiKeySpi.getApiKeyValue(Long)` | 存在 | 可用 |
| `SessionSpi.createSession` / `RunSpi.createRun/getRun/updateRun` / `InstanceSpi.getInstance` | 均存在 | 可用 |
| `ChatRuntimeService.chat/createRun/startRun` | 存在（runtime-orchestration） | task 触发执行入口 |
| Flyway | 最高 V30 | V31/V32 可用 |
| 认证 | `AuthInterceptor` 拦 `/api/**` | `/api/v1/api/*` 自动要求 Bearer，无需改白名单 |
| 权限种子模式 | `V29` 示范 MENU+BUTTON+授权 | V32 仿照 |

## 六、质量复核与修复记录

### 复核结论（功能完整性）

| # | 问题 | 修复 |
|---|------|------|
| F1 | `KernelLlmService.invokeSync` 超时/中断路径直接抛异常，逃逸出方法 → completions 路由 fallback 失效、整体 500 | 超时/中断收敛为 failed `InvokeResult`（永不抛）；`CompletionsServiceImpl.execute` 路由循环内再包 try/catch 兜底继续下一 route |
| F2 | task `submit` 先落库 QUEUED，`resolveInstanceId/createSession/chat` 任一异常 → 任务永远卡 QUEUED | try/catch 包裹，失败置 `FAILED` + `result_json.error` 后 rethrow |
| F3 | `stop()` 不取消轮询 future，已取消任务仍空转至 30min；轮询不认 run 状态 CANCELLED | `taskId→ScheduledFuture` 注册表（`pollFutures`），stop/终态取消；轮询补 CANCELLED 终态分支 |
| F4 | 新控制器缺 `@WithTenant`，与全仓惯例不符、非超管可跨租户访问 | 决策：**仅 `AdminCompletionsController` 加 `@WithTenant`**；外部 Completions/TaskController 作为平台级能力不加（已在附录 A 记录） |
| F5 | `TaskVO` 无 `goal`，轮询方看不到任务目标 | `TaskVO` 补 `goal`/`remark` |

### 复核结论（代码质量 / AGENTS.md）

- **魔法值**：新增模块级枚举 `CompletionsEnum`（`STATUS_ACTIVE`/`CALL_STATUS_SUCCESS`/`CALLER_ANONYMOUS`）与 `TaskEnum`（`STATUS_QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED`），替换三个 service 内全部裸字符串（含 `CompletionsPromptServiceImpl` 的 `"ACTIVE"`）；`ENABLED` 复用既有 `InstanceEnum.STATUS_ENABLED`；`titleOf` 的 `60` 抽常量。
- **i18n**：后端无 i18n 机制，既有约定 = 校验注解英文 + ErrorCode 中文 message/userTip；新代码一致，未引入额外机制。
- **类型化入参**：新增 `CompletionsInput`，`CompletionsSpi.execute(Long, CompletionsInput)`；`ChatCompletionsReq.input` 保留 Map（请求体边界），controller 转 VO。
- **通用字段**：V31 四表缺 `remark`（call 表缺 `updated_*`）。因 V31/V32 可能已在本地 DB 应用过，**不改已应用迁移**，新增 **V33** 用 `ADD COLUMN IF NOT EXISTS` 补齐；实体/VO 同步加字段。
- **已知限制（有意保留）**：`input_schema/output_schema` 仅存储不校验；task 无 list 分页接口；`markTerminal` 先查后更存在理论竞态。

### 决策补充（附录 A 追加）

| # | 项 | 结论 |
|---|----|------|
| 5 | 新控制器租户隔离 | **仅 AdminCompletionsController 加 `@WithTenant`**；外部 Completions/TaskController 不加（平台级能力，接受按 id 访问） |
| 6 | V32 授权范围 | **保持现状**（ADMIN+USER 全量含 delete） |
| 7 | 通用字段补齐方式 | **新增 V33 迁移**（不改已应用的 V31/V32） |
| 8 | completions 输入入参类型 | **引入 `CompletionsInput` VO**（动态字段袋子 + `of(Map)`） |

### 验证

- `mvn test` 全绿 **56 例**（原 50 + `KernelLlmServiceTest` 3 + `CompletionsServiceTest` 新增 fallback 2 + `AgentTaskServiceTest` 新增 submit 失败 1）。

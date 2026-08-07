# AgentSphere 三层能力开放落地文档

> 版本：v1.0
> 依据：`4A-design-v2.md`（招聘系统 4A v2）+ `implementation-plan.md`（P0-P3 落地方案）+ 现有 AgentSphere 模块分层
> 定位：在 AgentSphere 现有 `model 层 → instance 层` 之外，新增 **completions 层**（单次 LLM）与 **task 层**（多轮任务），形成三层能力开放架构
> 说明：表设计遵循 AgentSphere 现有规范（status/delete_flag/tenant_id/created_by/updated_by/created_at/updated_at）；按 4A 方式输出

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

- 管理接口（admin/completions）：走 AgentSphere admin 权限（RBAC）
- 调用接口（api/completions、api/tasks）：Bearer token（AgentSphere 用户维度），按用户路由模型/计费

### 1.4 数据流

```
调用方（HRI）→ POST /api/v1/api/completions { completionsId, input }
  → CompletionsService: 查 completions + active prompt → 渲染 prompt → 解析 route → 调 LLM → 返回
调用方（HRI）→ POST /api/v1/api/tasks { goal, context, expectedOutput }
  → TaskService: 落 agent_task → 创建 session → 触发 run → 轮询 → 返回结果
```

---

## 二、应用架构 (Application Architecture)

### 2.1 模块结构（★新增 2 个 Maven 模块）

```
agent-sphere-completions/                    ★ 单次 LLM 能力
├── agent-sphere-completions-controller/     # CompletionsController, AdminCompletionsController
├── agent-sphere-completions-domain/         # AgentCompletions, AgentCompletionsPrompt, AgentCompletionsCall
├── agent-sphere-completions-dtvo/           # CompletionsVO, ChatCompletionsReq, ChatCompletionsResp, CreateCompletionsDTO, CreatePromptDTO, CompletionsCallVO
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

**模块依赖**：
- `agent-sphere-completions` → `agent-sphere-model-spi`（RouteSpi/ModelProviderSpi/ApiKeySpi）+ `agent-sphere-runtime-kernel`（RouteListBuilder/KernelLlmService）+ `agent-sphere-common`
- `agent-sphere-tasks` → `agent-sphere-instance-spi`（InstanceSpi/SessionSpi/RunSpi）+ `agent-sphere-common`

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

**task**：
```
POST /api/v1/api/tasks
  { goal, context, expectedOutput, config }
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
    AgentCompletions c = completionsMapper.selectById(completionsId);
    AgentCompletionsPrompt prompt = promptMapper.selectById(c.getActivePromptId());
    // 1. 渲染 prompt：{{input}} 整体注入 + 按 inputSchema 字段级占位
    List<ChatMessage> messages = renderPrompt(prompt, input);
    // 2. 解析 route 链（primary + fallback）
    List<ModelRouteFullVO> routes = routeListBuilder.fromRouteId(c.getModelRouteId());
    // 3. 遍历 route：取 apiKey → KernelLlmService.stream → 成功返回/失败切 fallback
    for (ModelRouteFullVO route : routes) {
        String apiKey = apiKeySpi.getApiKeyValue(route.getApiKeyId());
        ChatCompletionRequestDTO req = buildRequest(messages, c.getConfig());
        result = llmService.invokeSync(route.getCompany(), route.getBaseUrl(), apiKey,
                route.getModelName(), req);   // 复用 KernelLlmService 封装
        if (result.success) break;
    }
    // 4. 记录 completions_call
    completionsCallService.record(...);
    return resp;
}
```

#### tasks 模块

| 类 | 职责 | 核心方法 |
|----|------|---------|
| `TaskController` | 任务创建/查询/停止 | `POST /api/v1/api/tasks`, `GET /{id}`, `POST /{id}/stop` |
| `AgentTaskService` | 任务编排（委托 instance） | `submit`, `get`, `stop` |

**`AgentTaskService.submit` 执行链**：
```java
public TaskVO submit(CreateTaskDTO dto) {
    AgentTask task = new AgentTask(); task.setGoal(dto.getGoal()); ...; taskMapper.insert(task);
    // 委托 instance：创建 session + run
    SessionVO session = sessionSpi.createSession(new CreateSessionDTO(agentInstanceId, title, null));
    RunVO run = runSpi.createRun(new CreateRunDTO(session.getId(), "task", dto.getGoal()));
    task.setSessionId(session.getId()); task.setRunId(run.getId()); task.setStatus("RUNNING");
    taskMapper.updateById(task);
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

### 3.2 实体对应

| 表 | 实体 | 备注 |
|----|------|------|
| `agent_completions` | `AgentCompletions` | 主表 |
| `agent_completions_prompt` | `AgentCompletionsPrompt` | 版本表 |
| `agent_completions_call` | `AgentCompletionsCall` | 调用记录 |
| `agent_task` | `AgentTask` | 任务 |

### 3.3 prompt 版本切换

- 主表 `active_prompt_id` 指向当前生效的 prompt 版本
- 新增版本：`POST /admin/completions/{id}/prompts` → 插入 `agent_completions_prompt`（version 递增）
- 切换：`PUT /admin/completions/{id}/activate { promptId }` → 更新 `active_prompt_id`
- 执行时读 `active_prompt_id` 对应的 prompt

### 3.4 prompt 渲染规则

- `{{input}}`：整体 JSON 注入（input 序列化为 JSON 放入 prompt_user）
- 字段级占位：按 `input_schema` 定义，如 `{{candidate.name}}`、`{{position.title}}` → 从 input 中取对应字段替换

---

## 四、技术架构 (Technology Architecture)

### 4.1 复用组件

| 组件 | 模块 | 用途 |
|------|------|------|
| `RouteListBuilder` | runtime-kernel | routeId → provider（baseUrl/apiKeyId）+ fallback；**需暴露私有 `fromRouteId` 为 public**（现仅有 `fromInstance`/`fromContext`） |
| `ModelProviderSpi` | model-spi | 流式 LLM 调用 |
| `ApiKeySpi.getApiKeyValue` | model-spi | 解析 apiKey |
| `KernelLlmService` | runtime-kernel | 复用 LLM 调用（超时/事件/审计），需补一个同步 invoke 便捷方法 |
| `InstanceSpi`/`SessionSpi`/`RunSpi` | instance-spi | task 层委托 instance |

### 4.2 执行链

**completions**：`CompletionsService.execute` → 渲染 prompt → `RouteListBuilder.fromRouteId`（暴露）→ `ApiKeySpi` → `KernelLlmService.invokeSync`（fallback 遍历）→ 落 `agent_completions_call`

**tasks**：`AgentTaskService.submit` → 落 `agent_task` → `SessionSpi.createSession` → `RunSpi.createRun` → 轮询 `RunSpi.getRun` → 更新 `agent_task.result_json`

### 4.3 对现有代码的改动（最小侵入）

1. **`RouteListBuilder`**：`fromRouteId(Long)` 由 private 改为 public（供 completions 复用）
2. **`KernelLlmService`**：新增 `invokeSync(...)` 便捷方法（同步返回完整文本，内部用 `stream` + 收集），供 completions 直接调用
3. **父 `pom.xml`**：新增 `agent-sphere-completions`、`agent-sphere-tasks` 两个 module
4. **`bootstrap`**：依赖新模块 + Flyway 迁移 V31 自动执行

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

## 附录 A：待确认项

| # | 项 | 状态 |
|---|----|------|
| 1 | `KernelLlmService.invokeSync` 是否直接加入 runtime-kernel，或 completions 自带轻量调用 | 待定（倾向复用） |
| 2 | completions 按用户路由模型（tenantId 维度）或全局 route | 待定 |
| 3 | tasks 的 `instance_id` 来源（调用方指定 / 系统默认 agent） | 待定 |
| 4 | completions 成本统计口径（token → 金额） | 待定 |

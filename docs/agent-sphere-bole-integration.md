# Bole（HRI）接入 AgentSphere 集成方案

> 版本：v1.0
> 范围：Bole 招聘系统将现有 AI mock 场景切换为真实调用 agent-sphere 的 completions / tasks 能力
> 定位：本文档为 **agent-sphere 侧实现清单**，交付 as 团队按此落地；Bole 侧已按本文约定完成实现（真实调用 + mock 降级）

---

## 一、对接总览

Bole 后端调用 agent-sphere 两个接口族：

| Bole 业务 | as 接口 | 说明 |
|---|---|---|
| 简历解析 | `POST /api/v1/api/completions` | completionsId=1 |
| 5 维匹配 | `POST /api/v1/api/completions` | completionsId=2（当前无生产调用，预留） |
| AI 触达话术 | `POST /api/v1/api/completions` | completionsId=3 |
| 自然语言搜索 | `POST /api/v1/api/completions` | completionsId=4 |
| 组织收集 | `POST /api/v1/api/completions` | completionsId=5（当前纯 mock，预留） |
| 评估报告推荐理由 | `POST /api/v1/api/completions` | completionsId=6 |
| AI 面试题 | `POST /api/v1/api/completions` | completionsId=7 |
| 寻访任务派发 | `POST /api/v1/api/tasks` | 异步任务，含 callbackUrl |
| 寻访任务状态 | `GET /api/v1/api/tasks/{id}` | Bole 定时轮询 |

**调用方式**：Bole 后端服务直连（无 Authorization 头），请求体携带 `userId`（Bole 用户 id）。

---

## 二、completions 固定 id 约定

Bole 侧 `system_settings.agent_sphere_completions` 维护 code→id 映射（已 seed）：

| code | completionsId |
|---|---|
| resume_parse | 1 |
| five_dim_match | 2 |
| outreach | 3 |
| nl_search | 4 |
| org_collect | 5 |
| recommend_reason | 6 |
| interview_questions | 7 |

> ⚠️ **as 侧需保证上述 id 稳定**：completions seed 迁移必须**显式指定 id**（`INSERT ... (id, name, ...) VALUES (1, ...)`），不得依赖自增，否则 Bole 映射失效。

---

## 三、as 侧实现清单

### 3.1 completions seed（幂等 V 迁移）

创建 7 个 completions，**显式固定 id 1-7**，每项含：

- `name` / `description`：业务可读名
- `model_route_id`：绑定模型路由（见 3.3）
- `input_schema` / `output_schema`：JSON Schema（仅存储，as 不校验）
- `config`：如 `{"temperature":0.1}`
- `status = 'ACTIVE'`

每个 completions 至少一个 `agent_completions_prompt` 版本（`version=1`），并 `active_prompt_id` 指向它。prompt 建议：

| code | prompt_system / prompt_user 要点 |
|---|---|
| resume_parse | system：你是资深招聘顾问，从简历信息提取结构化字段。user：`{{input}}` |
| five_dim_match | system：按技能/职位/行业/目标公司/年限 5 维评分(0-100)。user：`{{input}}` |
| outreach | system：生成面向候选人的暖场触达话术。user：`{{input}}` |
| nl_search | system：将自然语言寻访需求解析为结构化筛选条件(industry/skills/city/education/minYears/maxYears/minSalary/summary)。user：`{{input}}` |
| org_collect | system：根据目标公司整理组织架构联系信息。user：`{{input}}` |
| recommend_reason | system：面向客户生成推荐理由。user：`{{input}}` |
| interview_questions | system：按岗位画像与简历差距生成面试题(questions:[{dimension,type,content}])。user：`{{input}}` |

prompt 支持字段占位：`{{input}}`（整体 JSON）与 `{{field}}`（input 点号取值）。

### 3.2 免 token 服务调用

- `CompletionsController` / `TasksController` 需接受 **Bole 后端直连**（无 `Authorization` 头）。
- 请求体带 `userId`（Long，Bole 用户 id），as 用于 `agent_completions_call.caller` 审计。
- **实现方式（as 自选）**：
  - a) 将该来源 IP/域名加入鉴权白名单（推荐，`AuthInterceptor` 扩展）
  - b) 或 as 侧约定共享签名头（Bole 未实现，需双方新增约定——当前按 a 落地）
- `userId` 反查：如需关联 as 用户，按 `agent_sso_identity`（provider_code='bole'）的 `subject` 反查（前提：Bole IdP 签发 token 的 `sub` = Bole userId，需 as 侧确认该映射）。

### 3.3 模型路由 seed

至少配置一条可用路由供 completions 绑定：

- `agent_model_provider`：如 DeepSeek（name/base_url/api_key_id）
- `agent_api_key`：对应 provider 的有效 key
- `agent_model_route`：provider_id + model_name + company='deepseek'，completions 的 `model_route_id` 指向它

### 3.4 completions 请求/响应契约（Bole 侧已按此实现）

**请求**：
```json
POST /api/v1/api/completions
{ "completionsId": 1, "userId": 1001, "input": { "candidateId": 5, "name": "张三" } }
```

**响应**：
```json
{ "content": "LLM 返回文本", "model": "gpt-4o", "usage": { "total_tokens": 42 } }
```

> Bole 解析 `content` 为 JSON 后按业务结构使用；解析失败时 Bole 侧降级 mock。

### 3.5 tasks 回调支持（callbackUrl）

- `CreateTaskDTO` 增加 `callbackUrl`（String，可选）：as 在任务**终态**（COMPLETED/FAILED/CANCELLED）时回调。
- 回调请求体：
  ```json
  POST {callbackUrl}
  { "asTaskId": 1, "status": "COMPLETED", "resultJson": "{\"reply\":\"...\"}", "remark": "" }
  ```
- Bole 侧回调端点：`POST /api/v1/sourcing/tasks/callback`（已实现，白名单免鉴权）。
- 回调失败：as 侧重试或仅依赖 Bole 轮询兜底（Bole 已实现 5min 轮询）。

### 3.6 任务状态查询（已有，供 Bole 轮询）

- `GET /api/v1/api/tasks/{id}` 返回 `TaskVO{ id, status, resultJson, ... }`
- 状态枚举：`QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED`
- Bole 侧映射：COMPLETED→success、FAILED→failed、CANCELLED→stopped

---

## 四、Bole 侧已实现内容（供 as 对齐）

| 项 | 状态 |
|---|---|
| `AiGatewayService`：请求 `{completionsId, userId, input}` + mock 降级 | ✅ |
| `resolveCompletionsId`：读 `system_settings.agent_sphere_completions` | ✅ |
| 5 个 completions 调用方传 userId | ✅ |
| `SourcingService.dispatch`：tasks 请求含 `userId` + `callbackUrl` | ✅ |
| `SourcingTaskSyncService`：每 5min 轮询近 1h 未终态任务 | ✅ |
| `POST /api/v1/sourcing/tasks/callback` 回调端点 | ✅ |
| V48 迁移：`agent_sphere_completions` 映射 + `agent_sphere_callback_base_url` | ✅ |

---

## 五、as 侧验收要点

1. 7 个 completions seed 应用后，`agent_completions` 的 id 为 1-7（显式），且均绑定有效 `model_route_id` + 激活 prompt。
2. Bole 后端直连 `POST /api/v1/api/completions`（带 `completionsId`+`userId`，无 Authorization 头）能返回 `content`。
3. Bole 直连 `POST /api/v1/api/tasks`（含 `callbackUrl`）能创建任务；终态后 Bole 回调端点收到 `{asTaskId, status, resultJson}`。
4. `agent_completions_call.caller` 记录 Bole userId（或 `bole:<userId>`）。

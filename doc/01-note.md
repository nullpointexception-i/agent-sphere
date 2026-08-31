# Skill 机制升级落地方案

> 目标：让 AgentSphere 的 Skill 从“可配置但不执行”的元数据，升级为可控、可观测、可嵌套的 ReAct 子 Agent。
> 覆盖范围：`agent-sphere/` 后端、`agent-sphere-ui/` 管理端。

## 一、决策锁定

- Skill 执行采用**嵌套 ReAct**，不是单次 LLM 调用，也不是简单 system prompt 注入。
- Skill 与主 Agent 复用同一个 `sessionId`、`runId`、`KernelContext` 和模型路由，不创建新的 session/run。
- Skill 内层可以调用工具，但必须经过 `allowTools` 白名单。
- 内层 reasoning、工具调用状态、LLM 交互记录照常发送和落库。
- 管理端一并支持 Skill 启用/禁用和批量状态操作。
- 默认示例技能暂时保留 `{"prompt":"请按配置执行"}`，兼容解析后再替换业务内容。
- `allowTools` 采用默认拒绝：缺省或空数组表示不允许调用工具。
- 普通工具和 Skill 使用独立 timeout，不能为了 Skill 全局放大普通工具超时。

## 二、现状问题

### 2.1 运行时问题

当前 Skill 相关代码主要位于：

- `agent-sphere-runtime/agent-sphere-runtime-orchestration/.../pipeline/ContextPreparer.java`
- `agent-sphere-runtime/agent-sphere-runtime-kernel/.../tool/ToolExecutor.java`
- `agent-sphere-runtime/agent-sphere-runtime-kernel/.../prompt/RunPromptBuilder.java`
- `agent-sphere-capability/agent-sphere-capability-skill/`
- `agent-sphere-resource-template/.../ResourceTemplates.java`

当前流程存在以下问题：

1. `ResourceTemplates.DEFAULT` 中的示例定义是 `{"prompt":"请按配置执行"}`。
2. `ContextPreparer.parseSkillDefinition` 要求 `parameters` 和 `promptTemplate`，示例技能因此被静默跳过。
3. `ToolExecutor` 的 Skill 分支只返回固定字符串：

   ```json
   {"info":"Skill tool delegated to next turn"}
   ```

4. `promptTemplate` 只写入 `execBinding`，没有任何执行代码读取它。
5. 没有 Skill 子循环、递归保护、工具白名单和 Skill timeout。
6. 内层 LLM/工具事件没有 scope，无法区分主 Agent 和 Skill 执行。

### 2.2 管理端问题

- Skill definition 是一个裸 JSON TextArea，没有格式说明和校验。
- Skill 详情弹窗不展示 definition。
- Skill 状态字段存在，但没有启用/禁用接口和 UI。
- GET 接口没有统一使用 `capability:skill:read` 权限。
- `CapabilityFullVO` 对 Skill 没有完整返回 description/toolRef。
- 当前没有 Skill 相关自动化测试。

## 三、Skill Definition V1

### 3.1 标准格式

```json
{
  "version": 1,
  "parameters": {
    "type": "object",
    "properties": {
      "keyword": {
        "type": "string"
      }
    },
    "required": ["keyword"]
  },
  "promptTemplate": "请围绕 {{keyword}} 完成任务并返回最终结果。",
  "allowTools": [
    "builtin:chrome",
    "mcp:12:search",
    "cli:3",
    "skill:8"
  ]
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `version` | 定义版本，当前为 `1` |
| `parameters` | Skill LLM 工具入参 JSON Schema |
| `promptTemplate` | Skill 子 Agent 的任务指令 |
| `allowTools` | Skill 子 Agent 可使用的工具白名单 |

### 3.2 模板渲染

使用安全的 `SkillPromptRenderer`，不执行表达式、脚本、SpEL 或 Shell。

支持以下占位符：

```text
{{keyword}}
{{input}}
{{candidate.name}}
```

规则：

- 字符串值直接插入；
- 对象和数组使用 JSON 序列化后插入；
- 缺少字段返回结构化错误；
- Prompt 长度受配置限制；
- 不支持任意表达式计算。

### 3.3 遗留定义兼容

遗留格式：

```json
{"prompt":"请按配置执行"}
```

运行时归一化为：

```json
{
  "version": 1,
  "parameters": {
    "type": "object",
    "properties": {}
  },
  "promptTemplate": "请按配置执行",
  "allowTools": []
}
```

遗留 Skill 可以正常执行，但默认不拥有工具权限，必须在管理端明确配置 `allowTools`。

## 四、工具引用和白名单

### 4.1 稳定工具引用

`RuntimeTool` 增加 `toolRef` 字段，由 `ContextPreparer` 生成：

| 类型 | toolRef 格式 |
|---|---|
| Builtin | `builtin:<internalName>` |
| MCP | `mcp:<capabilityId>:<nativeToolName>` |
| CLI | `cli:<capabilityId>` |
| Skill | `skill:<skillId>` |

不能用 `displayName` 作为权限标识，因为 displayName 可能被修改或国际化。

### 4.2 白名单执行规则

`allowTools` 同时控制三个位置：

1. 子 Agent 的 `ToolDefinitionDTO`，禁止的工具不发送给 LLM；
2. 子 Agent system prompt 中的工具列表；
3. `ToolExecutor` 实际执行入口，防止模型伪造工具名绕过限制。

父 Skill 调用子 Skill 时使用权限交集：

```text
effectiveTools = parentAllowedTools ∩ childAllowedTools
```

这样子 Skill 不能通过间接调用获得父 Skill 没有的权限。

### 4.3 特殊限制

- `ask_clarification` 默认禁止在嵌套 Skill 中调用；
- 当前 Skill 出现在 `skillStack` 中时拒绝再次调用；
- 超过 `max-nested-depth` 时返回结构化错误；
- 禁用状态的 Skill 不进入 runtime 工具列表。

## 五、内核执行架构

### 5.1 ToolExecutionContext

新增类型化 `ToolExecutionContext`，不要用 ThreadLocal 传递嵌套状态：

```text
sessionId
runId
KernelContext kernelContext
List<RuntimeTool> sessionTools
int skillDepth
List<Long> skillStack
String parentToolCallId
Instant deadline
Set<String> inheritedAllowedToolRefs
```

`SessionRunner` 创建 root context，Skill 调用时创建 child context。

### 5.2 LlmTurnExecutor

从 `SessionRunner.runTurn()` 抽取共用的 `LlmTurnExecutor`，负责：

- 模型路由解析；
- fallback 路由；
- LLM 流式调用；
- reasoning 和 tool call 解析；
- 单轮 timeout；
- run/session cancel 检查；
- LLM 交互审计。

主 Agent 和 Skill 子 Agent 都使用该组件，避免维护两套不一致的 LLM 调用逻辑。

### 5.3 ToolBatchExecutor

新增 `ToolBatchExecutor`，统一处理：

- 工具提交和并发执行；
- 每工具独立 timeout；
- cancel 中断；
- `ToolCallStatus` 事件；
- 工具结果收集；
- 唯一 publishId 生成。

主 Agent 和 Skill 子 Agent 共用该组件。主 Agent 现有的 todo/clarification 阶段逻辑保留在 `SessionRunner`，通用执行逻辑下沉到 `ToolBatchExecutor`。

### 5.4 SkillReActExecutor

新增：

```text
agent-sphere-runtime/
  agent-sphere-runtime-kernel/
    .../skill/SkillReActExecutor.java
```

执行流程：

1. 读取 Skill definition；
2. 校验 `skillDepth`、`skillStack` 和 deadline；
3. 使用 `SkillPromptRenderer` 渲染调用参数；
4. 根据 `allowTools` 和父级权限过滤 RuntimeTool；
5. 构造子 Agent messages：

   ```text
   system = 原实例 systemPrompt
            + Skill promptTemplate
            + 白名单工具说明

   user = Skill 调用参数 JSON
   ```

6. 调用 `LlmTurnExecutor`；
7. 没有 tool call 时返回最终内容；
8. 有 tool call 时使用 `ToolBatchExecutor` 执行；
9. 把工具结果追加到子循环 messages；
10. 继续下一轮，直到最终回答、超时、取消或达到循环上限；
11. 返回结果给外层 `ToolExecutor`。

`SkillReActExecutor` 通过 `ObjectProvider<ToolExecutor>` 获取 ToolExecutor，避免 SkillReActExecutor 与 ToolExecutor 的 Spring 构造循环依赖。

### 5.5 ToolExecutor 改造

将当前 Skill 分支：

```java
return JSON_INFO_SKILL_DELEGATED;
```

替换为真实调用：

```text
ToolExecutor
  -> SkillReActExecutor
      -> LlmTurnExecutor
      -> ToolBatchExecutor
      -> ToolExecutor（子工具）
```

子工具调用必须携带新的 `ToolExecutionContext`，不能重新从全局状态推断 depth 或权限。

## 六、Timeout、取消与事件

### 6.1 配置

```yaml
skill:
  execution-enabled: true
  max-sub-loop-count: 8
  max-nested-depth: 3
  execution-timeout: 10m
  max-prompt-chars: 20000
  max-result-chars: 2000
```

普通工具继续使用：

```yaml
tool:
  execution-timeout: 60s
```

### 6.2 FiberSet 改造

当前 `FiberSet` 对整个批次使用一个 timeout，需要增加单工具 timeout：

```java
submit(String callId, Duration timeout, Supplier<String> task)
```

规则：

- 普通 MCP/Builtin/CLI 使用 `tool.execution-timeout`；
- Skill 使用 `skill.execution-timeout`；
- Skill 内层工具使用“剩余 Skill deadline”和工具 timeout 的较小值；
- 超时只中断对应工具，不拖延或误杀同批其他工具。

### 6.3 事件策略

内层事件照常推送，但不能污染主回复：

- reasoning → `reasoning_token`；
- 内层工具 → `tool_call_started/succeeded/failed`；
- 内层 LLM → `LlmInteractionType.SKILL_EXECUTION`；
- 内层普通文本 → Skill reasoning 展示；
- 内层普通文本不发送为主 run 的 `content_token`。

`RuntimeEventDataVO` 增加可选字段：

```text
executionScope: MAIN | SKILL
skillDepth
skillId
parentToolCallId
```

现有前端可以忽略新增字段，后续再增加 Skill 折叠展示。

内层 publishId 使用父级前缀，避免工具记录冲突：

```text
skill-42-tool-call-1
skill-42-tool-call-2
```

## 七、Definition 解析和校验

### 7.1 SkillDefinitionParser

新增独立 Parser，替代 `ContextPreparer` 中的私有解析方法。

建议模型：

```java
public record SkillDefinition(
        int version,
        String parametersSchemaJson,
        String promptTemplate,
        List<String> allowTools) {
}
```

Parser 负责：

- V1 格式解析；
- legacy `prompt` 兼容；
- allowTools 归一化；
- 结构化错误返回；
- 不再“日志 warning 后静默跳过”。

### 7.2 SkillDefinitionValidator

创建和更新 Skill 时校验：

- definition 是合法 JSON；
- `parameters` 是 object；
- parameters 是合法 JSON Schema；
- `promptTemplate` 非空；
- `allowTools` 是字符串数组；
- 工具引用格式合法；
- Prompt/definition 不超过限制。

输入参数执行前也根据 `parameters` 做校验。

优先复用已有 `com.networknt:json-schema-validator`，必要时将通用 JSON Schema 校验抽到 `agent-sphere-util`。

## 八、Skill 后端管理

### 8.1 状态接口

单个修改：

```http
PUT /api/v1/capability/skill/{id}/status
```

```json
{"status":"ENABLED"}
```

批量修改：

```http
POST /api/v1/capability/skill/batch/status
```

```json
{
  "ids": [1, 2, 3],
  "status": "DISABLED"
}
```

新增类型化 DTO：

- `UpdateSkillStatusDTO`；
- `BatchUpdateSkillStatusDTO`。

新增 SPI/service 方法：

```text
updateStatus(id, status)
batchUpdateStatus(ids, status)
```

### 8.2 权限和返回值

- GET 列表和详情增加 `capability:skill:read`；
- 状态修改需要 `capability:skill:update`；
- runtime 只注册 ENABLED Skill；
- `CapabilityFullVO` 返回 `description`、`status`、`toolRef`；
- 清理空的 `SkillConstants`，或将其改为真实共享常量；
- Skill 执行异常使用 `SKILL_EXECUTION_FAILED`。

## 九、管理端 UI

文件：

```text
agent-sphere-ui/src/pages/capabilities/skill/index.tsx
```

### 9.1 结构化编辑

表单拆分为：

- name；
- description；
- promptTemplate TextArea；
- parameters JSON Schema TextArea；
- allowTools 多行输入，每行一个稳定工具引用；
- definition 查看样例；
- JSON 校验错误提示。

编辑 legacy definition 时自动反解析：

```json
{"prompt":"..."}
```

详情弹窗展示完整 definition、status、创建/更新时间。

### 9.2 批量状态操作

复用现有 row selection，增加：

- 批量启用；
- 批量禁用；
- 操作前确认；
- 操作成功/失败数量提示；
- status 列；
- 单行 Switch 快捷操作。

实例能力绑定页面同时展示 `toolRef`，方便配置 Skill 的 `allowTools`。

## 十、测试方案

### 10.1 Parser/Validator

`SkillDefinitionParserTest`：

- V1 格式；
- legacy prompt；
- 缺少 parameters；
- 缺少 promptTemplate；
- 非法 JSON；
- allowTools 缺失、空数组、非法数组；
- 工具引用格式。

`SkillDefinitionValidatorTest`：

- JSON Schema 合法性；
- 参数缺失；
- 超长 prompt；
- 非法工具引用。

### 10.2 白名单和递归

`SkillToolPolicyTest`：

- 白名单工具进入 tool definitions；
- 非白名单工具不进入 tool definitions；
- 模型伪造工具名时执行入口仍拒绝；
- 父子 Skill 权限取交集；
- 自调用拒绝；
- 超过最大 depth 拒绝；
- `ask_clarification` 在子循环中拒绝。

### 10.3 嵌套 ReAct

`SkillReActExecutorTest`：

- 首轮直接返回最终结果；
- 首轮调用 MCP/Builtin/CLI；
- 工具结果进入下一轮；
- 下一轮返回最终结果；
- 多层 Skill 调用；
- timeout；
- run/session cancel；
- 子循环超限；
- 结果截断；
- 内层事件和 LLM 审计记录。

### 10.4 管理接口

覆盖：

- 单个启用/禁用；
- 批量启用；
- 批量禁用；
- 空 ID 列表；
- 非法状态；
- GET 权限；
- runtime 跳过 DISABLED Skill。

## 十一、实施顺序

1. 新增 `SkillDefinition`、Parser、Validator、PromptRenderer。
2. 修复 `ContextPreparer`，支持 legacy definition、toolRef 和 disabled 状态。
3. 抽取 `LlmTurnExecutor`，保证主 Agent 行为不变。
4. 抽取 `ToolBatchExecutor`，增加单工具 timeout。
5. 增加 `ToolExecutionContext` 并调整 `SessionRunner`/`ToolExecutor` 调用链。
6. 实现 `SkillReActExecutor`。
7. 替换 `ToolExecutor` 的 Skill stub。
8. 增加递归、cancel、deadline、allowTools 防护。
9. 增加 Skill 单个/批量状态接口。
10. 补齐 RBAC、CapabilityFullVO、错误码和审计信息。
11. 完成管理端结构化编辑和批量状态操作。
12. 补齐单测和集成测试。
13. 运行全量验证：

    ```bash
    mvn clean test
    ```

## 十二、验收标准

- ENABLED Skill 能被 LLM 看到为 `skill_<id>`；
- legacy `{"prompt":"..."}` 不再被静默跳过；
- Skill 可以多轮调用白名单内的 MCP/Builtin/CLI/Skill；
- 白名单外工具不可见且无法执行；
- 子循环工具结果能驱动下一轮推理；
- Skill 最终结果能正确返回主 Agent；
- 内层 reasoning、工具状态、LLM 交互可观测并落库；
- 内层中间文本不会污染主 Agent 最终回复；
- Skill 自调用和循环调用被阻止；
- run/session cancel 能停止主循环和嵌套 Skill；
- Skill timeout 不会误杀其他普通工具；
- DISABLED Skill 不进入 runtime 工具列表；
- 管理端支持批量启用/禁用；
- definition 校验失败时给出明确错误；
- 现有主 Agent、MCP、CLI、Builtin 行为不回归；
- 全量 `mvn clean test` 通过。

## 十三、关键风险

| 风险 | 控制措施 |
|---|---|
| Skill 无限递归 | `skillStack` + `max-nested-depth` |
| Skill 间接越权 | 父子 allowTools 取交集，执行入口二次校验 |
| 内层文本污染主回复 | 内层普通文本只走 Skill scope reasoning |
| Skill 被普通工具 60s 超时误杀 | FiberSet 支持每工具独立 timeout |
| 工具事件 ID 冲突 | publishId 使用父级 Skill 前缀 |
| 子 Skill 触发用户澄清 | 嵌套上下文默认禁用 `ask_clarification` |
| 旧 definition 无法使用 | Parser 兼容 legacy prompt，并默认无工具权限 |
| 主 Agent 重构回归 | 先抽取共用执行器，再保持主路径测试全绿 |

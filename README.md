# AgentSphere

**AgentSphere** 是一个面向 AI Agent 的通用自动化编排平台。它通过 LLM 驱动的决策引擎，结合多种能力（内置工具、MCP 协议、CLI 执行、浏览器操作等），实现从**感知→规划→执行→反馈**的完整闭环。

> 浏览器自动化只是 AgentSphere 的能力之一。通过 MCP（Model Context Protocol）可以接入任意外部服务（Jira、GitHub、数据库、云平台等），构建真正的通用 Web Agent。

> 项目基于 **4A 架构**（Analysis, Architecture, Algorithm, Administration）设计，强调模块化、可扩展性和可观测性。

---

## 1. Analysis — 需求分析

### 1.1 业务目标

让 AI Agent 能够像人类一样理解任务、规划步骤、执行操作，并学习改进。AgentSphere 不局限于浏览器自动化，而是作为一个通用编排平台，支持通过多种能力通道与真实世界交互。

### 1.2 核心能力

| 能力通道 | 集成方式 | 应用场景示例 |
|---------|---------|-------------|
| **MCP (Model Context Protocol)** | 标准协议接入 | Jira 操作、GitHub 管理、数据库查询、云平台运维 |
| **Chrome 浏览器** | Chrome Extension + DOM API | 网页信息采集、CMS 内容发布、Web 系统配置 |
| **CLI 命令行** | Process 执行 | 本地脚本运行、服务器管理、构建部署 |
| **Builtin 工具** | Java SPI | HTTP 请求、网页解析、待办管理 |
| **Skill 复合技能** | 多步任务编排 | 跨系统工作流、数据同步、自动化报告 |

### 1.3 核心非功能需求

| 需求 | 要求 |
|------|------|
| **实时性** | 用户能实时看到浏览器被操作的过程 |
| **稳定性** | 工具执行超时后有兜底机制，不阻塞后续流程 |
| **安全性** | 所有操作经过用户本地 Chrome 执行，不经过云端 |
| **可扩展** | 工具 SPI 机制，可注册任意类型的能力 |
| **可观测** | 每次工具调用都有日志/事件记录，可回溯 |

---

## 2. Architecture — 系统架构

### 2.1 整体架构

![agentsphere-architecture.svg](agent-sphere-readme/agentsphere-architecture.svg)


### 2.2 核心组件

#### 2.2.1 SessionRunner（ReAct 引擎）

管理一次 AI 会话的完整执行生命周期，实现 **Plan → Act → Observe → Learn** 循环：

```
run(sessionId)
  │
  ├─ [Plan]   messages = system + history + user
  │           shouldCompact(messages, route)→ compact → reassemble
  │
  ├─ [Act]    runTurn() → LLM 流式调用
  │   ├─ LLM 返回 text → allContent.append → 继续
  │   ├─ LLM 返回 tool_calls → 执行工具
  │   │   ├─ FiberSet.submit(tool1, tool2, ...)  ← 并行
  │   │   └─ FiberSet.awaitAll() → 收集结果
  │   └─ 工具结果 → append tool-role messages
  │
  ├─ [Observe] 工具回调 → 结果写入 messages
  │   ├─ Chrome → POST /chrome/callback
  │   ├─ WebFetch → HTTP response
  │   └─ CLI → stdout / exit code
  │
  ├─ [Learn]   下一轮循环（loopCount++）
  │   ├─ hasToolCalls = true → 跳过 promoteInput
  │   └─ 带着工具结果再次调用 LLM
  │
  └─ 终止条件
      ├─ LLM 返回 stop 且无 tool_calls → COMPLETED
      ├─ maxLoopCount 耗尽 → 强制总结
      ├─ 用户取消 → CANCELLED
      └─ 无更多输入 → break
```

**与 ReAct 模式的对齐：**

```
ReAct 经典模式           AgentSphere 实现
──────────────────────────────────────────────
Thought（思考）          LLM 推理 + tool_calls 决策
Action（行动）           executeJS / navigate / click / type
Observation（观察）      tool 返回结果写入 messages
Final Answer（最终回答）  LLM 返回 stop，无 tool_calls
```

#### 2.2.2 Capability 能力层

| 能力类型 | 实现 | 说明 | 示例 |
|---------|------|------|------|
| **MCP (Model Context Protocol)** | MCP Server 客户端 | 标准协议，接入任意 MCP Server | Jira、GitHub、Slack、数据库 |
| **Builtin (内置工具)** | SPI: `CapabilityBuiltinToolSpi` | Java SPI 扩展 | WebFetch、WebRead、Chrome、Todowrite |
| **Chrome 浏览器** | Chrome Extension 桥接 | DOM 操作 + 实时视觉反馈 | 导航、点击、填表、截图 |
| **CLI (命令行)** | `ProcessBuilder` 执行 | 本地或远程 Shell | Git 操作、构建部署、系统管理 |
| **Skill (复合技能)** | 多步任务编排 | LLM 驱动的任务分解 | 跨系统工作流 |

#### 2.2.3 Chrome Extension（浏览器桥接）

```
Content Script (页面上下文)
  ├─ checkAuth() → localStorage 读取 token
  ├─ SSE 状态 Toast → 浮动提示
  ├─ domToJSON() → DOM → 结构化 JSON
  └─ 执行 click / type / getContent / executeJS
        │ chrome.runtime.sendMessage
        ▼
Service Worker (后台)
  ├─ SSE 连接 → fetch + ReadableStream
  ├─ 会话跟随 → URL 变化自动重建 SSE
  ├─ 标签页管理 → 受控标签页追踪 + tabId 透传
  └─ 操作回调 → POST /chrome/callback

Popup (状态面板)
  ├─ 连接状态显示
  ├─ URL 配置
  └─ 操作日志
```

---

## 3. Algorithm — 核心算法

### 3.1 ReAct 执行循环

AgentSphere 的核心循环遵循 **ReAct (Reasoning + Acting)** 模式，将 LLM 的推理能力和工具执行能力有机结合：

```
每轮循环（Loop Iteration）:
┌────────────────────────────────────────────────────────────┐
│  messages = [system, history..., user_input]               │
│  messages += assistant(tool_calls)  ← LLM 生成的工具调用    │
│  messages += tool(results)          ← 工具执行结果          │
│  messages += assistant(text)        ← LLM 的文本回复       │
│                                                            │
│  LLM 看到的是完整的对话历史 + 工具调用链                     │
└────────────────────────────────────────────────────────────┘
```

**消息构成：**

```
[
  {role: "system",    content: "你是一个浏览器助手..."},
  {role: "user",      content: "帮我查询广州天气"},
  {role: "assistant", tool_calls: [{id: "call_1", name: "navigate", args: "..."}}]},
  {role: "tool",      tool_call_id: "call_1", content: '{"tabId": 42, "url": "..."}'},
  {role: "assistant", content: "广州明天天气是..."},
  {role: "user",      content: "明天出门该准备什么"},
  ...
]
```

**多轮工具调用示例：**

```
Loop 0: LLM → todowrite(创建计划)
        → LLM → navigate(天气网站)
Loop 1: LLM → getContent(提取页面结构)
Loop 2: LLM → executeJS(解析具体数据)
Loop 3: LLM → todowrite(更新状态)
        → LLM → stop(输出结果) ✅ 完成
```

### 3.2 多级记忆体系 (Memory System)

AgentSphere 实现了多级记忆系统，覆盖从持久化到运行时缓存的完整链路：

```
┌────────────────────────────────────────────────────────────────────┐
│  记忆层级 / Memory Level                                            │
│                                                                    │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │  L1: 运行时上下文 (KernelContext)                             │  │
│  │  · 当前 session 的 KernelContext（TTL: 30min）                │  │
│  │  · 包含 tools, modelRoute, fallbackRoutes                    │  │
│  │  · 存储在 SessionRunner.contexts 的 ConcurrentHashMap        │  │
│  │  · run() 结束后自动移除                                      │  │
│  └──────────────────────┬──────────────────────────────────────┘  │
│                         │                                         │
│  ┌──────────────────────▼──────────────────────────────────────┐  │
│  │  L2: 对话消息 (Messages)                                     │  │
│  │  · 当前 run 的 messages ArrayList（LLM 所见即所得）          │  │
│  │  · 包含 system + user + assistant(tool_calls) + tool(result) │  │
│  │  · 每次 runTurn() 时发送给 LLM                               │  │
│  │  · Loop 之间积累，compaction 后部分被摘要替代                 │  │
│  └──────────────────────┬──────────────────────────────────────┘  │
│                         │                                         │
│  ┌──────────────────────▼──────────────────────────────────────┐  │
│  │  L3: LLM 交互记录 (agent_llm_interaction_record)             │  │
│  │  · 每次 LLM 调用的完整记录（请求体、响应、耗时、状态）       │  │
│  │  · 类型: CHAT_REPLY / COMPACTION / TITLE_GENERATION         │  │
│  │  · 保留原始 request body 和 response body                   │  │
│  │  · 用于调试、审计、性能分析                                  │  │
│  └──────────────────────┬──────────────────────────────────────┘  │
│                         │                                         │
│  ┌──────────────────────▼──────────────────────────────────────┐  │
│  │  L4: 工具调用记录 (agent_tool_call_record)                   │  │
│  │  · 每次工具调用的完整记录                                     │  │
│  │  · 字段: callId, stepId, toolName, argumentsJson,           │  │
│  │    compressedArguments, artifact, compressedArtifact, status │  │
│  │  · 生命周期: PENDING → RUNNING → SUCCEEDED / FAILED         │  │
│  │  · 结果写时压缩 (jsonCompress, 2000 char limit)              │  │
│  │  · 用于 HistoryLoader 回放、观测面板展示                      │  │
│  └──────────────────────┬──────────────────────────────────────┘  │
│                         │                                         │
│  ┌──────────────────────▼──────────────────────────────────────┐  │
│  │  L5: 压缩摘要 (agent_compact_record)                         │  │
│  │  · 当上下文 token 超过 budget 时触发                          │  │
│  │  · 由专区 summarizer 模型生成结构化摘要                       │  │
│  │  · 字段: compactedUptoRunId, summaryBefore, summaryAfter    │  │
│  │  · 游标: compactedUptoRunId 标记已压缩的 run                 │  │
│  │  · 后续 HistoryLoader 跳过已压缩的 run                       │  │
│  └──────────────────────┬──────────────────────────────────────┘  │
│                         │                                         │
│  ┌──────────────────────▼──────────────────────────────────────┐  │
│  │  L6: 会话持久化 (agent_session)                              │  │
│  │  · 一次用户会话的元数据（title, summary, status）            │  │
│  │  · 包含会话级别摘要（compaction 后更新）                      │  │
│  │  · 用于会话列表展示、run 历史关联                              │  │
│  └─────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

#### 记忆层级详细说明

| 层级 | 存储位置 | 生命周期 | 容量 | 用途 |
|------|---------|---------|------|------|
| L1: KernelContext | ConcurrentHashMap | run 期间 (TTL 30min) | 1 per session | 工具列表、模型路由 |
| L2: Messages | ArrayList | run 期间 | 数十轮对话 | LLM 输入输出 |
| L3: LLM Interaction | PostgreSQL | 永久 | 可配置 | 调试审计 |
| L4: Tool Call | PostgreSQL | 永久 | 无限 | 回放、观测 |
| L5: Compact Record | PostgreSQL | 永久 | 累计 | 上下文压缩 |
| L6: Session | PostgreSQL | 永久 | 1 per session | 元数据 |

#### 3.2.1 上下文组装 (Context Assembly)

`HistoryLoader` 负责从持久化存储中加载历史消息，组装成 LLM 的上下文：

```
HistoryLoader.load(sessionId)
  │
  ├─ 1. 读取最新 compact record
  │      → 如果有: 插入 [Conversation summary] system 消息
  │      → 确定 compactedUptoRunId 游标
  │
  ├─ 2. 加载游标之后的所有 run（无 limit）
  │
  ├─ 3. 收集每个 run 的 tool call 记录
  │      → 查 agent_tool_call_record 表
  │      → 包含 compressedArtifact（已在写入时压缩）
  │
  ├─ 4. 预算控制（从最新向旧累加）
  │      → 优先保留最近的工具结果
  │      → 超 budget 的标记 [Tool result omitted]
  │
  └─ 5. 按序组装 messages
       → [system] 压缩摘要
       → [user] 历史用户消息
       → [assistant] tool_calls 请求
       → [tool] 工具结果
       → [assistant] 历史助理回复
       → ... (每个 run 重复)
       → [user] 当前用户输入
```

**工具结果压缩流程：**

```
RuntimeEventListener.SUCCEEDED
  ├─ artifact（原始）→ 写入 agent_tool_call_record.artifact
  └─ jsonCompress(artifact, 2000) → .compressed_artifact
                                      ↓
                                HistoryLoader 读 .compressed_artifact
                                → 直接用于 messages（不再重新压缩）
```

#### 3.2.2 上下文压缩 (Compaction)

当 messages 的估算 token 超过 `maxInputTokens × budget-ratio` 时触发：

```
shouldCompact(messages, route) == true
  │
  ├─ compact(sessionId, runId, ctx)
  │   ├─ 加载游标之后所有 run
  │   ├─ 从最新向旧估算每个 run 的 token
  │   │   → 超出 budget 的部分标记为待压缩
  │   ├─ buildCompactInput → 拼接 [User:] + Tool[] + [Assistant:]
  │   │   → 工具结果用 jsonCompress(artifact, 500) 压缩
  │   │   → 受 compactionInputLimit (maxInputTokens × 0.5) 截断
  │   ├─ callLLM → 专用 summarizer 生成摘要
  │   │   → summaryMaxLen = inputToken × 2/3
  │   │   → 提示 LLM 输出必须比输入短
  │   └─ 写入 agent_compact_record
  │       → compactedUptoRunId = 最新被压缩的 run ID
  │
  └─ messages.clear() → 重新组装
     → historyLoader 现在加载的是压缩后的数据
     → shouldCompact 再次检查 → 可能还需压缩（最多 3 次）
```

**压缩链完整流程：**

```
每次 LLM 调用前
  ├─ 查历史 → HistoryLoader.load()
  │            ↓ 跳过 compactedUptoRunId 之前的 run
  │            ↓ 回放之后的 run（含工具记录）
  │            ↓ 超过 TOOL_RESULT_HISTORY_CHARS_BUDGET 的截断
  │
  ├─ 组消息
  │   [system] 压缩摘要（如果有）
  │   [user]   + [assistant(tool_calls)] + [tool(results)] + [assistant(text)]
  │   ...（每个未压缩的 run）
  │   [user] 当前输入
  │
  ├─ shouldCompact()
  │   ├─ 估算 messages 的 token
  │   ├─ 比较 budget = maxInputTokens × budget-ratio
  │   ├─ 未超 → 直接发 LLM ✅
  │   └─ 超出 → 触发 compaction
  │              → 写入 compact record
  │              → clear + 重新组装 → 重试（最多 3 次）
  │
  └─ runTurn(messages) → LLM
```

#### 3.2.3 工具调用记录状态机

```
                   ┌──────────┐
                   │  PENDING  │  ← LLM 返回 tool_calls 时创建
                   └────┬─────┘
                        │
                        ▼
                   ┌──────────┐
                   │  RUNNING  │  ← FiberSet 开始执行
                   └────┬─────┘
                        │
              ┌─────────┴─────────┐
              ▼                   ▼
       ┌──────────┐         ┌──────────┐
       │SUCCEEDED │         │  FAILED   │
       └──────────┘         └──────────┘
```

每条记录包含：
- `callId` — LLM 生成的工具调用 ID（如 `call_abc123`）
- `argumentsJson` — 原始入参
- `compressedArguments` — 入参 JSON 压缩版（写时压缩）
- `artifact` — 原始返回结果
- `compressedArtifact` — 结果 JSON 压缩版（写时压缩）
- 用于 HistoryLoader 回放、观测面板展示、审计

#### 3.2.4 工具结果压缩策略

```java
jsonCompress(node, depth, maxValueChars) {
  if (depth > 5) return "[deep nested]";

  if (node instanceof Map) {
    // 递归压缩每个值
    return map.mapValues(v -> jsonCompress(v, depth+1, maxValueChars))
  }

  if (node instanceof List) {
    if (list.size() <= 5) return list.map(v -> jsonCompress(v, depth+1))
    // 大数组: 保留前 3 个 + 总数
    return { _count: 13, _showing: 3, items: [...] }
  }

  if (node instanceof String) {
    if (text.length() <= maxValueChars) return text
    // 长字符串: 前 100 + 省略 + 后 50
    return text[0..100] + "...[+ N chars]...\n" + text[-50..-1]
  }

  return node // Number, Boolean 直通
}
```

### 3.3 模型路由与 Fallback

AgentSphere 提供了多层次的模型容错机制，确保 LLM 调用的高可用性。

#### 路由配置

```
ModelRoute (主路由)
  ├─ provider_id → 供应商 (DeepSeek / OpenAI / 自定义)
  ├─ model_name → 模型 (deepseek-v4-flash / gpt-4o)
  ├─ maxInputTokens → 输入上限（驱动压缩预算计算）
  ├─ maxOutputTokens → 输出上限
  └─ fallback_ids → 备选路由列表

ModelProvider (供应商)
  ├─ base_url → API 地址
  ├─ api_key_id → 密钥引用
  └─ config → 供应商配置 (JSON)
```

#### Fallback 执行流程

```
SessionRunner.runTurn()
  │
  ├─ resolveRoutes() → 构建路由列表
  │   ├─ 从 KernelContext 获取（直接指定）
  │   └─ 从 Instance 获取（管理后台配置）
  │       ├─ 主路由 (primary)
  │       └─ Fallback 路由 (fallbackIds)
  │
  ├─ FallbackRouteExecutor.execute(routes)
  │   ├─ Attempt #1: primary route
  │   │   ├─ success → LLM 流 ✅
  │   │   └─ exception → log.warn → 自动切换
  │   ├─ Attempt #2: fallback 1
  │   │   └─ ...
  │   └─ All failed → RuntimeException
  │
  └─ compactionService.shouldCompact()
      → 使用实际 route 的 maxInputTokens 计算 budget
      → 在 execute 回调内检测（确保和 LLM 调用同源）
```

#### 压缩预算计算

```
budget = maxInputTokens × budget-ratio (默认 0.7)

示例:
  Route: GLM-4.1V-Thinking-Flash, maxInputTokens=1_000_000
  → budget = 1_000_000 × 0.7 = 700_000 tokens
  → 当 messages 超过 700K token → 触发压缩

动态调整:
  budget-ratio: 0.5  → 更早触发（保留更多上下文质量）
  budget-ratio: 0.8  → 更晚触发（节省压缩开销）
```

#### 超时参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `llm.connect-timeout` | 30s | 连接 LLM API 的超时 |
| `llm.read-timeout` | 60s | 读取响应的超时 |
| `llm.stream-read-timeout` | 120s | 流式读取超时 |
| `llm.stream-timeout` | 120s | 流式调用总超时 |
| `runner.turn-timeout` | 180s | 单轮 LLM 调用总超时 |

### 3.4 浏览器操作流程

```
LLM 决策需要操作浏览器
  │
  ├─ 调用 builtin_5 (Chrome Tool)
  ├─ CapabilityBuiltinToolChrome.execute()
  │   ├─ 构建 ChromeCommandDTO (action, url, selector, text, code, tabId, append)
  │   ├─ SseManager.sendBySession(sessionId, dto)
  │   │   → SSE 事件 → Extension Service Worker
  │   ├─ CompletableFuture.get(10s)  ← 等待回调
  │   │
  │   ├─ [navigate]  chrome.tabs.create({ url, active:false })
  │   │              → requestIdleCallback → callback POST
  │   │              → 返回 { tabId, url, redirected }
  │   │
  │   ├─ [click]     chrome.tabs.sendMessage(tabId, { action, params })
  │   │              → 3-phase XPath text matching
  │   │              → callback POST
  │   │
  │   ├─ [type]      chrome.tabs.sendMessage(tabId, { action, params, append })
  │   │              → input.value / textContent (contenteditable)
  │   │              → append=true 时追加而非替换
  │   │              → InputEvent dispatch
  │   │
  │   ├─ [getContent] chrome.tabs.sendMessage(tabId, { action, params, mode })
  │   │              → mode=summary: 结构化摘要 (inputs/buttons/forms/navLinks/...)
  │   │              → 不传 mode: 完整 DOM JSON
  │   │              → callback POST
  │   │
  │   └─ [executeJS] chrome.debugger.Runtime.evaluate 绕过 CSP
  │                  → callback POST
  │
  ├─ ChromeCallbackController.receive(commandId, result)
  │   → ChromePendingStore.complete(commandId, result)
  │   → CompletableFuture 完成 → execute() 返回
  │
  └─ LLM 收到操作结果 → 继续决策
```

### 3.5 多标签页管理

```
LLM 操作两个页面:
  navigate(url: "https://github.com/...") → tabId: 1644814353
  navigate(url: "https://zhuanlan.zhihu.com/...") → tabId: 1644814350

LLM 可分别操作:
  getContent(tabId: 1644814353) → GitHub 页面
  getContent(tabId: 1644814350) → Zhihu 页面

无 tabId 时 → 操作最后导航的标签页（controlledTabId）
```

### 3.6 超时与取消链

```
SessionRunner.run()
  │
  ├─ tool.execution-timeout: 60s     → FiberSet.awaitAll()
  │   └─ 超时 → future.cancel(true) → 中断虚拟线程
  │       └─ Chrome 工具：CompletableFuture.get() 抛出 CancellationException
  │       └─ WebFetch：HttpClient.send() 抛出 InterruptedIOException
  │       └─ CLI：Process.destroyForcibly() 强制终止
  │
  ├─ turn-timeout: 180s              → CountDownLatch.await()
  │   └─ 超时 → llmFuture.cancel(true) → 中断 LLM 流
  │       └─ KernelLlmService → 虚拟线程检测 isCancelled → 退出
  │
  └─ maxLoopCount: 128               → while 循环上限
      └─ 最后 1 轮注入强制总结指令

用户取消:
  POST /api/v1/runtime/{sessionId}/run/{runId}/stop
    → SessionRunner.cancelRun(runId)
    → CANCELLED_RUNS 标记
    → 循环检测 → break → 发布 CANCELLED 事件
```

### 3.7 会话跟随（Session Following）

```
Content Script → checkAuth 轮询
  │
  ├─ 读取 location.pathname → /chat/{sessionId}
  ├─ 变化 → chrome.runtime.sendMessage({ type: 'auth' })
  │   → Service Worker → connectSSE() → 新 session
  │
  └─ 新标签页 → chrome.tabs.create({ url, active:false })
      → controlledTabId 记录
      → tabId 透传（LLM 可指定 tabId 操作多个标签页）
```

---

## 4. Administration — 运维与管理

### 4.1 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `session.idle-timeout` | 30m | 会话空闲超时 |
| `session.max-concurrent-runs` | 10 | 最大并发执行数 |
| `runner.max-loop-count` | 128 | 单次 run 最大循环次数 |
| `runner.turn-timeout` | 180s | 单轮 LLM 调用超时 |
| `runner.compaction.budget-ratio` | 0.7 | 压缩触发阈值（maxInputTokens 比例） |
| `llm.connect-timeout` | 30s | LLM API 连接超时 |
| `llm.read-timeout` | 60s | LLM API 读取超时 |
| `llm.stream-timeout` | 120s | 流式调用总超时 |
| `tool.max-parallel` | 3 | 工具最大并行数 |
| `tool.execution-timeout` | 60s | 单批工具执行超时 |
| `tool.submit-timeout` | 30s | 工具提交超时 |

### 4.2 可观测性 (Observability)

AgentSphere 提供三层观测体系：

#### 4.2.1 实时事件 (SSE Events)

```
LLM 调用链实时推送:

content_token     → "广州明天天气..."
reasoning_token   → "🤔 用户问天气，我需要打开天气网站"
                  → "⚙️ navigate: calling..."
                  → "⚙️ navigate: succeeded ✅"
                  → "⚙️ getContent: calling..."
                  → "⚙️ getContent: succeeded ✅"
                  → "⏹️ Run cancelled" 或 "✅ Run completed"
```

| SSE 事件 | 触发时机 | 前端效果 |
|---------|---------|---------|
| `content_token` | LLM 文本生成 | 打字机效果 |
| `reasoning_token` | LLM 推理、工具状态 | 推理面板 |
| `browser_operation` | Chrome 操作指令 | Extension 执行 |
| `run_running` | Run 开始 | 状态指示 |
| `run_completed` | Run 完成 | 完成通知 |
| `run_failed` | Run 失败 | 错误提示 |
| `tool_call_started` | 工具 PENDING | 工具调用列表 |
| `tool_call_succeeded` | 工具完成 | ✅ 图标 |
| `tool_call_failed` | 工具失败 | ❌ 图标 |
| `compaction_running` | 压缩开始 | 推理面板 |
| `compaction_completed` | 压缩完成 | 推理面板 |

#### 4.2.2 Run Activity API

提供完整的工具调用历史查询：

```
GET /api/v1/instance/runs/{runId}/activities?offset=0&limit=20

Response:
{
  "total": 20,
  "records": [
    { "activityType": "llm_interaction",
      "modelName": "deepseek-v4-flash",
      "interactionType": "CHAT_REPLY",
      "durationMs": 2588,
      "requestBody": "{...}",
      "responseBody": "{...}",
      "success": true },
    { "activityType": "tool_call",
      "toolName": "builtin_5",
      "displayName": "builtin.CapabilityBuiltinToolChrome",
      "argumentsJson": "{...}",
      "artifact": "{...}",
      "status": "SUCCEEDED" }
  ]
}
```

#### 4.2.3 会话面板 (Session Panel)

| 视图 | 内容 |
|------|------|
| **Run 列表** | 按 session 查看历史 run，展示 userMessage + assistantReply |
| **工具调用列表** | 当前会话最新的工具调用记录（按创建时间倒序） |
| **待办列表** | 当前会话的 todo 清单，支持状态跟踪 |
| **操作日志** | Chrome Extension popup 中的历史操作记录 |

### 4.3 日志体系

| 日志级别 | 输出 | 用途 |
|---------|------|------|
| `ControllerLogAspect` | INFO | API 请求/响应记录 |
| `ChromeCallbackController` | WARN | 浏览器操作失败 |
| `FiberSet` | WARN | 工具超时/失败 |
| `SessionRunner` | INFO | 执行轮次和状态 |
| `LlmInteractionPersistListener` | DEBUG | LLM 交互记录持久化 |
| `RuntimeEventListener` | DEBUG | 工具调用生命周期事件 |

### 4.4 关键部署步骤

```bash
# 1. 编译后端
cd agent-sphere
mvn compile -pl agent-sphere-bootstrap -am

# 2. 启动后端
mvn spring-boot:run -pl agent-sphere-bootstrap

# 3. 启动前端
cd agent-sphere-ui
npm run dev

# 4. 加载 Chrome Extension
# Chrome → chrome://extensions → 开发者模式 → 加载已解压的扩展
# 选择 agent-sphere-chrome-extension 目录

# 5. 配置 URL
# 点击扩展图标 → Settings Tab
# Frontend URL: http://localhost:8000
# Backend URL:  http://localhost:8080
```

### 4.5 架构决策记录 (ADR)

| 决策 | 方案 | 原因 |
|------|------|------|
| **SSE vs WebSocket** | Server-Sent Events | 单向推送无需客户端确认，浏览器原生支持 |
| **fetch+ReadableStream vs EventSource** | fetch + ReadableStream | MV3 Service Worker 中 EventSource 无法携带 Authorization header |
| **Virtual Threads** | Java 21 Virtual Threads | 简化并发模型，每个工具一个虚拟线程 |
| **Chrome Extension 独立部署** | 独立项目 | 不耦合 Web UI，权限隔离 |
| **Multi-emitter SSE** | `List<SseEmitter>` per session | Web UI 和 Extension 共享同一 SSE 通道 |
| **FiberSet cancel(true)** | `CompletableFuture.cancel(true)` | 超时时有效中断阻塞的虚拟线程 |
| **工具结果写时压缩** | `RuntimeEventListener` 压缩后写入 `compressed_artifact` | HistoryLoader 读取时无需重新压缩，减少重复计算 |
| **基于 token 预算的压缩触发** | `shouldCompact` 在 `runTurn` 的 execute 回调内 | 使用实际调用的 model route 的 maxInputTokens，确保准确 |
| **compaction 游标** | `compactedUptoRunId` 标记已压缩的 run | HistoryLoader 跳过已压 run，只加载之后的 |
| **压缩保护循环** | 最多 3 次重试 | 防止网络波动导致压缩失败时无限循环 |

### 4.6 能力扩展

#### 添加新的内置工具

```java
@Component
public class CapabilityBuiltinToolMyTool implements CapabilityBuiltinToolSpi {
    @Override
    public BuiltinToolEnum getToolType() { return BuiltinToolEnum.MY_TOOL; }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + "MyTool");
        info.setDescription("Description for LLM");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(MyToolDTO.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(MyToolResultVO.class));
        return info;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        MyToolDTO dto = (MyToolDTO) ctx;
        // 实现逻辑
        return new MyToolResultVO(/* result */);
    }
}
```

---

## 5. 项目结构

```
agent-sphere/                          # 后端主项目
├── agent-sphere-bootstrap/           # 应用启动入口
├── agent-sphere-common/              # 公共库 (配置、异常、工具)
├── agent-sphere-infrastructure/      # 基础设施 (AOP、鉴权)
├── agent-sphere-instance/            # 实例/会话管理
│   ├── controller/                   #   API 控制器
│   ├── service/                      #   业务逻辑
│   ├── repository/                   #   数据访问
│   └── domain/                       #   领域模型
├── agent-sphere-model/               # 模型管理
│   ├── provider/                     #   LLM 供应商
│   ├── route/                        #   路由管理
│   └── api-key/                      #   API Key
├── agent-sphere-capability/          # 能力层 (可扩展)
│   ├── builtin/                      #   内置工具 SPI
│   │   ├── tool-chrome/              #     Chrome 浏览器操作
│   │   ├── tool-webfetch/            #     HTTP 网页获取
│   │   ├── tool-webread/             #     网页内容读取
│   │   └── tool-todowrite/           #     待办任务管理
│   ├── mcp/                          #   MCP 协议 (Jira/GitHub/DB...)
│   ├── cli/                          #   命令行执行
│   └── skill/                        #   复合技能
├── agent-sphere-runtime/             # 运行引擎
│   ├── kernel/                       #   核心执行
│   │   ├── runner/                   #     SessionRunner
│   │   ├── async/                    #     FiberSet
│   │   ├── service/                  #     CompactionService
│   │   └── tool/                     #     ToolExecutor
│   └── orchestration/                #   编排层
│       ├── sse/                      #     SSE Manager
│       ├── chrome/                   #     Chrome 指令 Handler
│       └── handler/                  #     RuntimeEventListener
├── agent-sphere-util/                # 工具类
└── pom.xml                           # 父 POM

agent-sphere-chrome-extension/        # Chrome 扩展 (独立项目)
├── manifest.json                     # 扩展配置
├── background.js                     # Service Worker — SSE + 指令路由
├── content.js                        # Content Script — DOM 操作 + Toast
├── popup.html / popup.js             # 状态面板 (Info/Settings/Logs)
└── icon-128.png                      # 图标

agent-sphere-ui/                      # Web UI (独立项目，UmiJS)
├── src/
│   ├── pages/chat/                   # 聊天页面
│   └── services/agentSphere/         # API 客户端
└── package.json
```

## 6. 技术栈

| 领域 | 技术 |
|------|------|
| **后端运行时** | Java 21, Spring Boot 3.4, Virtual Threads |
| **数据库** | PostgreSQL, Flyway 迁移 |
| **缓存/分布式锁** | Redis (Redisson) |
| **前端** | React, UmiJS, Ant Design Pro |
| **Chrome 扩展** | Manifest V3, Service Worker, Content Script |
| **实时通信** | SSE (Server-Sent Events), 多 emitter 广播 |
| **工具协议** | MCP (Model Context Protocol, Streamable HTTP) |
| **API 安全** | Bearer Token, @WithTenant 多租户 |
| **LLM 集成** | SPI 供应商抽象, 自动 Fallback 路由 |

## 7. MCP 集成示例

AgentSphere 支持通过 MCP 协议接入任意外部服务。以 Jira 为例：

```bash
# 1. 部署 Jira MCP Server
npx @roovet/jira-mcp --port 3100

# 2. 在 AgentSphere 管理后台添加 MCP 能力
curl -X POST /api/v1/capability/mcp \
  -d '{"name":"Jira MCP","serverUrl":"http://localhost:3100","serverType":"streamable-http"}'

# 3. 绑定到 Agent 实例
curl -X POST /api/v1/instance/instance-capabilities \
  -d '{"instanceId":1,"capabilityType":"mcp","capabilityId":1}'

# 4. 用户只需在聊天中发指令
# "帮我查询 Jira 上我的未完成任务"
# → LLM 调用 MCP tool → Jira API → 返回结果
```

## 8. License

MIT License

Copyright (c) 2026 Buukle

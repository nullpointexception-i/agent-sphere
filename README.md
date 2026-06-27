This project is an AI Agent orchestration platform. Driven by an LLM-based decision engine and combined with capabilities (built-in tools, MCP protocol, CLI execution, browser automation, etc.), it implements a primary closed loop of **Perception → Planning → Execution → Feedback**.

It supports configuring different model providers: OpenAI, DeepSeek, QuickRouter (relay station), BigModel (Zhipu AI), LiteLLM.
---

Screenshots

![ui-register.png](agent-sphere-readme/ui-register.png)

![ui-chat.png](agent-sphere-readme/ui-chat.png)

![ui-chat-toolcalls.png](agent-sphere-readme/ui-chat-toolcalls.png)

![ui-artifact-document.png](agent-sphere-readme/ui-artifact-document.png)

▶ [Click to watch the video demo](https://www.bilibili.com/video/BV1bxjk6VEG6/?vd_source=c85252e0a26262947782e1b02533fb15)

[![Video preview](agent-sphere-readme/ui-preview.gif)](https://www.bilibili.com/video/BV1bxjk6VEG6/?vd_source=c85252e0a26262947782e1b02533fb15)

## 1. Quick Start for Development

See: [QUICK_START.md](QUICK_START.md)

## 2. Architecture

### 2.1 Overall Structure

![agentsphere-architecture.png](agent-sphere-readme/agentsphere-architecture.png)

### 2.2 Core Components

#### 2.2.1 SessionRunner (ReAct Engine)

Manages the complete execution lifecycle of an AI session, implementing the **Plan → Act → Observe → Learn** loop:

![SessionRunner.run execution lifecycle](agent-sphere-readme/session-runner-flow.png)

**Alignment with the ReAct pattern:**

![ReAct pattern alignment](agent-sphere-readme/react-mode.png)

#### 2.2.2 Capability Layer

| Capability Type | Implementation | Description | Examples |
|-----------------|----------------|-------------|----------|
| **MCP (Model Context Protocol)** | MCP Server client | Standard protocol, connects to any MCP Server | Jira, GitHub, Slack, databases |
| **Builtin (built-in tools)** | SPI: `CapabilityBuiltinToolSpi` | Java SPI extension | WebFetch, WebRead, Chrome, Todowrite, DocWrite |
| **Chrome Browser** | Chrome Extension bridge | DOM operations + real-time visual feedback | Navigate, click, fill forms, screenshot |
| **CLI (command line)** | `ProcessBuilder` execution | Local or remote shell | Git operations, build/deploy, system administration |
| **Skill (composite skills)** | Multi-step task orchestration | LLM-driven task decomposition | Cross-system workflows |

#### 2.2.3 Chrome Extension (Browser Bridge)

![Chrome Extension browser bridge structure](agent-sphere-readme/chrome-extension-structure.png)

---

## 3. Algorithm — Core Algorithms

### 3.1 ReAct Execution Loop

The core loop of AgentSphere follows the **ReAct (Reasoning + Acting)** pattern, combining the LLM's reasoning ability with tool execution ability:

![ReAct execution loop](agent-sphere-readme/react-loop.png)

**Message structure:**

```
[
  {role: "system",    content: "You are a browser assistant..."},
  {role: "user",      content: "Help me check the weather in Guangzhou"},
  {role: "assistant", tool_calls: [{id: "call_1", name: "navigate", args: "..."}}]},
  {role: "tool",      tool_call_id: "call_1", content: '{"tabId": 42, "url": "..."}'},
  {role: "assistant", content: "The weather in Guangzhou tomorrow is..."},
  {role: "user",      content: "What should I prepare for going out tomorrow"},
  ...
]
```

**Multi-turn tool call example:**

![Multi-turn tool call example](agent-sphere-readme/multi-loop-sequence.png)

### 3.2 Multi-level Memory System

AgentSphere implements a multi-level memory system covering the full chain from persistence to runtime caching:

![Multi-level Memory System](agent-sphere-readme/memory-system.png)

#### Memory Level Details

| Level | Storage | Lifecycle | Capacity | Purpose |
|-------|---------|-----------|----------|---------|
| L1: KernelContext | ConcurrentHashMap | During run (TTL 30min) | 1 per session | Tool list, model route |
| L2: Messages | ArrayList | During run | Dozens of turns | LLM input/output |
| L3: LLM Interaction | PostgreSQL | Permanent | Configurable | Debugging & audit |
| L4: Tool Call | PostgreSQL | Permanent | Unlimited | Replay, observation |
| L5: Compact Record | PostgreSQL | Permanent | Cumulative | Context compression |
| L6: Session | PostgreSQL | Permanent | 1 per session | Metadata |

#### 3.2.1 Context Assembly

`HistoryLoader` is responsible for loading historical messages from persistent storage and assembling them into the LLM context:

![HistoryLoader context assembly](agent-sphere-readme/history-loader.png)

**Tool result compression flow:**

![Tool result write-time compression flow](agent-sphere-readme/tool-result-compress.png)

#### 3.2.2 Context Compaction

Triggered when the estimated tokens of messages exceed `maxInputTokens × budget-ratio`:

![Context Compaction](agent-sphere-readme/compaction-flow.png)

**Full compression chain flow:**

![Full compression chain flow](agent-sphere-readme/compression-chain.png)

#### 3.2.3 Tool Call Record State Machine

![Tool call record state machine](agent-sphere-readme/tool-call-state-machine.png)

Each record contains:
- `callId` — Tool call ID generated by the LLM (e.g., `call_abc123`)
- `argumentsJson` — Original input arguments
- `compressedArguments` — Compressed version of input JSON (write-time compression)
- `artifact` — Original return result
- `compressedArtifact` — Compressed version of result JSON (write-time compression)
- Used by HistoryLoader for replay, observation panel display, and auditing

#### 3.2.4 Tool Result Compression Strategy

```java
jsonCompress(node, depth, maxValueChars) {
  if (depth > 5) return "[deep nested]";

  if (node instanceof Map) {
    // Recursively compress each value
    return map.mapValues(v -> jsonCompress(v, depth+1, maxValueChars))
  }

  if (node instanceof List) {
    if (list.size() <= 5) return list.map(v -> jsonCompress(v, depth+1))
    // Large array: keep first 3 + total count
    return { _count: 13, _showing: 3, items: [...] }
  }

  if (node instanceof String) {
    if (text.length() <= maxValueChars) return text
    // Long string: first 100 + ellipsis + last 50
    return text[0..100] + "...[+ N chars]...\n" + text[-50..-1]
  }

  return node // Number, Boolean pass-through
}
```

### 3.3 Model Routing and Fallback

AgentSphere provides a multi-level model fault-tolerance mechanism to ensure high availability of LLM calls.

#### Routing Configuration

![Model routing configuration](agent-sphere-readme/model-route-config.png)

#### Fallback Execution Flow

![Fallback execution flow](agent-sphere-readme/fallback-flow.png)

> Note: The compression budget calculation is based on the actual route's maxInputTokens, detected within the execute callback. See the formula below for details.

#### Compression Budget Calculation

```
budget = maxInputTokens × budget-ratio (default 0.7)

Example:
  Route: GLM-4.1V-Thinking-Flash, maxInputTokens=1_000_000
  → budget = 1_000_000 × 0.7 = 700_000 tokens
  → When messages exceed 700K tokens → trigger compaction

Dynamic adjustment:
  budget-ratio: 0.5  → Triggers earlier (preserves more context quality)
  budget-ratio: 0.8  → Triggers later (saves compression overhead)
```

#### Timeout Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `llm.connect-timeout` | 30s | Timeout for connecting to LLM API |
| `llm.read-timeout` | 60s | Timeout for reading response |
| `llm.stream-read-timeout` | 120s | Stream read timeout |
| `llm.stream-timeout` | 120s | Total timeout for streaming calls |
| `runner.turn-timeout` | 180s | Total timeout for a single LLM turn |

### 3.4 Browser Operation Flow

![Browser operation flow](agent-sphere-readme/browser-operation-flow.png)

### 3.5 Multi-tab Management

![Multi-tab management](agent-sphere-readme/multi-tab.png)

### 3.6 Timeout and Cancellation Chain

![Timeout and cancellation chain](agent-sphere-readme/timeout-cancel-chain.png)

### 3.7 Session Following

![Session Following](agent-sphere-readme/session-following.png)

---

## 4. Administration — Operations and Management

### 4.1 Configuration Reference

| Config Item | Default | Description |
|-------------|---------|-------------|
| `session.idle-timeout` | 30m | Session idle timeout |
| `session.max-concurrent-runs` | 10 | Maximum concurrent executions |
| `runner.max-loop-count` | 128 | Maximum loop count per run |
| `runner.turn-timeout` | 180s | Single LLM turn timeout |
| `runner.compaction.budget-ratio` | 0.7 | Compaction trigger threshold (ratio of maxInputTokens) |
| `llm.connect-timeout` | 30s | LLM API connection timeout |
| `llm.read-timeout` | 60s | LLM API read timeout |
| `llm.stream-timeout` | 120s | Total streaming call timeout |
| `tool.max-parallel` | 3 | Maximum parallel tool executions |
| `tool.execution-timeout` | 60s | Single batch tool execution timeout |
| `tool.submit-timeout` | 30s | Tool submission timeout |

### 4.2 Observability

AgentSphere provides a three-tier observation system:

#### 4.2.1 Real-time Events (SSE Events)

```
Real-time push of LLM call chain:

content_token     → "The weather in Guangzhou tomorrow..."
reasoning_token   → "🤔 The user is asking about weather, I need to open a weather website"
                  → "⚙️ navigate: calling..."
                  → "⚙️ navigate: succeeded ✅"
                  → "⚙️ getContent: calling..."
                  → "⚙️ getContent: succeeded ✅"
                  → "⏹️ Run cancelled" or "✅ Run completed"
```

| SSE Event | Trigger | Frontend Effect |
|-----------|---------|-----------------|
| `content_token` | LLM text generation | Typewriter effect |
| `reasoning_token` | LLM reasoning, tool status | Reasoning panel |
| `browser_operation` | Chrome operation command | Extension execution |
| `run_running` | Run starts | Status indicator |
| `run_completed` | Run completes | Completion notification |
| `run_failed` | Run fails | Error prompt |
| `tool_call_started` | Tool PENDING | Tool call list |
| `tool_call_succeeded` | Tool completes | ✅ icon |
| `tool_call_failed` | Tool fails | ❌ icon |
| `compaction_running` | Compaction starts | Reasoning panel |
| `compaction_completed` | Compaction completes | Reasoning panel |

#### 4.2.2 Run Activity API

Provides complete tool call history querying:

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

#### 4.2.3 Session Panel

| View | Content |
|------|---------|
| **Run List** | View historical runs by session, showing userMessage + assistantReply |
| **Tool Call List** | Latest tool call records for the current session (sorted by creation time descending) |
| **Todo List** | Todo checklist for the current session, with status tracking |
| **Operation Log** | Historical operation records in the Chrome Extension popup |

### 4.3 Logging System

| Logger | Level | Purpose |
|--------|-------|---------|
| `ControllerLogAspect` | INFO | API request/response logging |
| `ChromeCallbackController` | WARN | Browser operation failures |
| `FiberSet` | WARN | Tool timeout/failure |
| `SessionRunner` | INFO | Execution turns and status |
| `LlmInteractionPersistListener` | DEBUG | LLM interaction record persistence |
| `RuntimeEventListener` | DEBUG | Tool call lifecycle events |

### 4.4 Key Deployment Steps

```bash
# 1. Build the backend
cd agent-sphere
mvn compile -pl agent-sphere-bootstrap -am

# 2. Start the backend
mvn spring-boot:run -pl agent-sphere-bootstrap

# 3. Start the frontend
cd agent-sphere-ui
npm run dev

# 4. Load the Chrome Extension
# Chrome → chrome://extensions → Developer mode → Load unpacked
# Select the agent-sphere-chrome-extension directory

# 5. Configure URLs
# Click the extension icon → Settings Tab
# Frontend URL: http://localhost:8000
# Backend URL:  http://localhost:8080
```

### 4.5 Architecture Decision Records (ADR)

| Decision | Solution | Reason |
|----------|----------|--------|
| **SSE vs WebSocket** | Server-Sent Events | One-way push requires no client confirmation, natively supported by browsers |
| **fetch+ReadableStream vs EventSource** | fetch + ReadableStream | EventSource cannot carry Authorization headers in MV3 Service Worker |
| **Virtual Threads** | Java 21 Virtual Threads | Simplifies concurrency model, one virtual thread per tool |
| **Chrome Extension standalone deployment** | Independent project | Decoupled from Web UI, permission isolation |
| **Multi-emitter SSE** | `List<SseEmitter>` per session | Web UI and Extension share the same SSE channel |
| **FiberSet cancel(true)** | `CompletableFuture.cancel(true)` | Effectively interrupts blocking virtual threads on timeout |
| **Tool result write-time compression** | `RuntimeEventListener` compresses then writes to `compressed_artifact` | HistoryLoader reads without re-compression, reducing redundant computation |
| **Token budget-based compaction trigger** | `shouldCompact` inside `runTurn`'s execute callback | Uses the actual called model route's maxInputTokens for accuracy |
| **Compaction cursor** | `compactedUptoRunId` marks compacted runs | HistoryLoader skips compacted runs, only loads subsequent ones |
| **Compaction protection loop** | Max 3 retries | Prevents infinite loops when compaction fails due to network fluctuations |

### 4.6 Capability Extension

#### Adding a New Built-in Tool

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
        // Implementation logic
        return new MyToolResultVO(/* result */);
    }
}
```

---

## 5. Project Structure

![Project structure](agent-sphere-readme/project-structure.png)

## 6. Tech Stack

| Domain | Technology |
|--------|------------|
| **Backend Runtime** | Java 21, Spring Boot 3.4, Virtual Threads |
| **Database** | PostgreSQL, Flyway migrations |
| **Cache/Distributed Lock** | Redis (Redisson) |
| **Frontend** | React, UmiJS, Ant Design Pro |
| **Chrome Extension** | Manifest V3, Service Worker, Content Script |
| **Real-time Communication** | SSE (Server-Sent Events), multi-emitter broadcast |
| **Tool Protocol** | MCP (Model Context Protocol, Streamable HTTP) |
| **API Security** | Bearer Token, @WithTenant multi-tenancy |
| **LLM Integration** | SPI provider abstraction, automatic fallback routing |

## 7. MCP Integration Example

AgentSphere supports connecting to any external service via the MCP protocol. Taking Jira as an example:

```bash
# 1. Deploy the Jira MCP Server
npx @roovet/jira-mcp --port 3100

# 2. Add the MCP capability in the AgentSphere admin console
curl -X POST /api/v1/capability/mcp \
  -d '{"name":"Jira MCP","serverUrl":"http://localhost:3100","serverType":"streamable-http"}'

# 3. Bind it to an Agent instance
curl -X POST /api/v1/instance/instance-capabilities \
  -d '{"instanceId":1,"capabilityType":"mcp","capabilityId":1}'

# 4. Users simply send instructions in the chat
# "Help me check my unfinished tasks on Jira"
# → LLM calls MCP tool → Jira API → returns result
```

![MCP Configuration UI](agent-sphere-readme/ui-new-mcp.png)

## 8. License

MIT License

Copyright (c) 2026 Buukle

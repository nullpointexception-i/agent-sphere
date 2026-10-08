# Parallel Search MCP

[English](README.md)

该可选示例通过 AgentSphere 已有的 HTTP MCP 能力连接
[Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp)。
`https://search.parallel.ai/mcp` 支持匿名调用 `web_search` 和 `web_fetch`，
适合探索和轻量使用，无需 Parallel API key。匿名访问有较低的速率限制；
模型推理费用另计。现有能力和模型、供应商默认配置不变。

按[快速开始](../../QUICK_START-cn.md)启动自己的 AgentSphere 后端，准备具有
MCP 创建和测试权限的账号、curl 和 jq。在仓库根目录执行
[英文示例中的命令](README.md)：

1. 设置 `AGENT_SPHERE_URL` 和自己的登录令牌 `AGENT_SPHERE_TOKEN`。
2. 将 `mcp.json` POST 到 `/api/v1/capability/mcp`，保存返回的 `id`。
   重复创建会新增一条记录。
3. GET `/api/v1/capability/mcp/{id}/tools`，检查当前工具和输入 schema。
4. 调用 `/api/v1/capability/mcp/{id}/tools/web_search/call` 和
   `/api/v1/capability/mcp/{id}/tools/web_fetch/call`。
   相关搜索和抓取复用同一个随机 `session_id`。

也可在「能力 → MCP」页面按 `mcp.json` 填写：服务器类型为 `http`
（Streamable HTTP，不是旧版 SSE），请求头配置填写
`{"User-Agent":"agent-sphere/1.0.0"}`。API 的 `authConfig` 字段需要 JSON
字符串，示例已完成转义。该请求头标识出站 MCP 请求，不向 Parallel 添加
Authorization；AgentSphere 登录令牌仅用于认证自己的后端。

响应采用 MCP `content` 数组。即使 HTTP 状态成功，也要检查 `isError`。
搜索返回来源 URL 和摘要，抓取返回页面内容。遇到限流请稍后重试。
示例演示直接调用能力 API，无需 LLM。

## 在 Agent 会话中使用

创建 MCP 记录不会自动为 Agent 添加工具。选择一个已配置支持工具调用的模型路由的
实例，在实例能力设置中绑定 Parallel，状态设为 `ENABLED`。账号还需要
`instance:capability:bind` 权限。[英文示例](README.md#use-from-an-agent-session)
提供了同等的绑定 API 命令。为该实例新建聊天，要求搜索 MCP Streamable HTTP
官方文档、抓取搜索返回的相关来源，再结合来源 URL 解释传输机制。

`ContextPreparer` 自动发现工具 schema，并将模型可见的 `mcp_<id>_<index>` 映射为
`web_search` 和 `web_fetch`。`SessionRunner` 经 `ToolExecutor` 调用已保存的 MCP
配置，将结果交给下一次模型请求。请查看聊天中的工具结果以确认两次调用；
工具选择和回答质量取决于模型。禁用绑定不向模型暴露这些 MCP 工具，现有模型选择
和权限规则保持不变。

## 回归测试与可选实时验证

使用 Java 21 和 Maven，在 `agent-sphere/` 下执行：

```bash
mvn install -DskipTests
mvn test
mvn -pl agent-sphere-bootstrap test -Dtest=ParallelSearchAgentLoopTest
# 显式启用网络请求：真实匿名 Parallel 搜索和抓取。
mvn -pl agent-sphere-bootstrap test -Dtest=ParallelSearchAgentLoopTest -Dparallel.live=true
```

新测试通过真实 MCP 服务加载 `mcp.json`，通过真实实例绑定服务建立绑定，再运行
`ContextPreparer`、`SessionRunner`、`KernelLlmService` 和 `ToolExecutor`。
mapper 持久化、Redis 状态、模型供应商及会话输入、历史和运行记录等外围服务使用
测试替身，核心工具发现和调度逻辑保持真实。受控模型读取搜索结果中的 URL 发起
抓取，并根据抓取结果生成最终回复；断言验证下一次模型请求中的工具调用 ID 和
assistant/tool 消息顺序。本地 HTTP 服务检查发现、搜索和抓取的端点、User-Agent
及无凭证请求，禁用绑定测试验证不产生 MCP 请求。显式实时测试使用示例中的匿名端点
和同一 Agent 循环，不从环境或保存配置加载 Parallel key。

该测试验证运行链路，不验证外部模型的推理和回答质量，也不覆盖 HTTP 身份认证或
数据库/Redis 的持久化。网络错误或匿名限流导致的失败不能算通过。
后端已有源码扫描测试会排除绝对路径中含 `target` 的文件；完整测试请在路径不含
该词的检出目录运行。仓库没有 PR 测试 CI，需要本地执行。

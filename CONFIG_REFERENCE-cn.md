# 配置参考

AgentSphere 自身配置项的完整参考 —— `buukle.agent.*` 与 `hri-ai.*`。全部位于 `agent-sphere/agent-sphere-bootstrap/src/main/resources/application.yml`；下表默认值即该文件中的取值（键缺失时为类内默认值）。

Spring 标准块（`spring.*`、`logging.*`、`mybatis-plus.*`、`server.*`）有意不列出。

> 环境变量覆盖：`DB_HOST`、`DB_PORT`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT` 会叠加在 `spring.datasource.*` / `spring.redis.*` 之上。k3s 中它们来自 `backend-config` ConfigMap（`k8s/04-backend-config.yaml`）。

## `buukle.agent.session`

| 键 | 默认值 | 说明 |
|---|---|---|
| `idle-timeout` | `30m` | 会话空闲超时 |
| `max-concurrent-runs` | `10` | 最大并发执行数 |
| `cache-ttl` | `60m` | 运行时上下文缓存存活时间 |
| `cleanup-cron` | `0 30 4 * * ?` | 历史会话清理 cron（低峰期） |
| `cleanup-batch-size` | `200` | 每批处理的会话数 |
| `cleanup-max-batches` | `50` | 单轮批次上限 |
| `cleanup-batch-sleep-ms` | `200` | 批间休眠，让位给 autovacuum |

清理的保留天数与急停开关放在 `agent_system_config`（`config_group='session'`），不在此处 —— 改完无需发版。见 README §4.14。

## `buukle.agent.step`

| 键 | 默认值 | 说明 |
|---|---|---|
| `max-retries` | `3` | 步骤最大重试次数 |
| `execution-timeout` | `5m` | 步骤执行超时 |

## `buukle.agent.sse`

| 键 | 默认值 | 说明 |
|---|---|---|
| `buffer-size` | `1024` | SSE 缓冲区大小（字节） |
| `cache-ttl` | `30s` | SSE 事件缓存 TTL |
| `heartbeat-interval` | `15s` | SSE 心跳间隔 |

## `buukle.agent.chrome`

| 键 | 默认值 | 说明 |
|---|---|---|
| `extension-token` | `""` | Chrome 扩展访问桥接层时携带的共享令牌 |

## `buukle.agent.llm`

| 键 | 默认值 | 说明 |
|---|---|---|
| `log-body-max-length` | `128` | 请求体日志截断长度 |
| `connect-timeout` | `30s` | LLM API 连接超时 |
| `read-timeout` | `60s` | LLM API 读取超时 |
| `stream-read-timeout` | `120s` | 流式读取超时 |
| `stream-timeout` | `120s` | 流式总超时（含思考） |
| `stream-max-retries` | `2` | 流式瞬时失败（未收到任何分片）重试次数 |
| `stream-retry-delay` | `1s` | 流式重试固定退避 |

## `buukle.agent.runner`

| 键 | 默认值 | 说明 |
|---|---|---|
| `max-loop-count` | `128` | 单次 run 最大循环次数（`CHAT` 源，见 README §3.1a） |
| `max-silent-turns` | `2` | 使用工具后允许连续无工具回复的容错轮数，超过后严格模式失败 |
| `task-max-loop-count` | `2000` | 任务触发 run 的更高轮次上限（`TASK` 源） |
| `max-reasoning-chars` | `500000` | 单 run 推理落库长度上限（字符） |
| `turn-timeout` | `180s` | 单轮 LLM 调用超时 |
| `llm-turn-max-retries` | `3` | 单轮瞬时失败重试次数，指数退避 1s→2s→4s（README §3.1b） |
| `max-screenshots-per-run` | `20` | 单 run 浏览器截图观察注入上限（防烧 token） |
| `compaction.budget-ratio` | `0.7` | 压缩触发阈值（占最大输入 token 的比例） |

实例级 `agent_instance.max_loop_count` 会为该实例覆盖以上全部取值（`INSTANCE` 源，优先级仅次于已固化值）。完整解析顺序见 README §3.1a。

## `buukle.agent.tool`

| 键 | 默认值 | 说明 |
|---|---|---|
| `max-parallel` | `3` | 工具最大并行数 |
| `submit-timeout` | `30s` | 工具提交超时 |
| `execution-timeout` | `60s` | 单批工具执行超时 |
| `cli-timeout` | `30s` | CLI 工具超时 |

### `tool.web-read` / `tool.web-fetch`

| 键 | 默认值 | 说明 |
|---|---|---|
| `connect-timeout` | `10s` | 连接超时 |
| `read-timeout` | `30s` | 读取超时 |

### `tool.web-read-advanced`

| 键 | 默认值 | 说明 |
|---|---|---|
| `use-jina-fallback` | `true` | 启用 Jina Reader 降级 |
| `jina-reader-url` | `https://r.jina.ai/` | Jina Reader 服务地址 |
| `jina-api-key` | `""` | Jina API Key |
| `jina-engine` | `direct` | Jina 引擎模式（`direct` / `browser`） |
| `jina-connect-timeout` | `30` | Jina 连接超时（秒） |
| `jina-read-timeout` | `30` | Jina 读取超时（秒） |
| `jina-timeout-buffer` | `10` | Jina 超时缓冲（秒） |
| `html-to-markdown.max-content-length` | `2097152` | HTML 转 Markdown 最大长度（字节） |
| `html-to-markdown.exclude-tags` | `script,style,noscript,meta,link,header,footer,nav,iframe,embed,object` | HTML 转 Markdown 时丢弃的标签 |

### `tool.web-fetch-advanced`

| 键 | 默认值 | 说明 |
|---|---|---|
| `max-retries` | `3` | 最大重试次数 |
| `retry-backoff-multiplier` | `2` | 退避倍率 |
| `retry-delay-ms` | `1000` | 初始重试延迟（毫秒） |
| `max-redirects` | `5` | 最大重定向次数 |
| `max-response-bytes` | `5242880` | 最大响应体大小（字节） |

### `tool.js-render`

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 启用 JS 渲染 |
| `max-wait-millis` | `10000` | 最大等待时间（毫秒） |
| `background-js-timeout` | `3000` | 后台 JS 超时（毫秒） |
| `max-pool-size` | `3` | 浏览器池最大大小 |

## `buukle.agent.wait`

`wait` 工具的时长边界与重试上限是代码常量（`WaitConstants`）、不是配置：`MIN_WAIT_MS = 100`、`MAX_WAIT_MS = 600000`、`MAX_RESUME_ATTEMPTS = 5`。见 README §3.8a。

| 键 | 默认值 | 说明 |
|---|---|---|
| `wake-cron` | `*/10 * * * * ?` | 唤醒补偿扫描。主路径是每条等待记录一个内存定时器；副本重启会让定时器全丢，因此这一层按 `wake_at` 兜底（CAS 保证不重复恢复） |
| `reconcile-interval` | `PT60S` | 卡住 `WAITING` 的对账扫描间隔 |
| `reconcile-stale-minutes` | `15` | 判定 `WAITING` 卡住的阈值（分钟），须大于 `MAX_WAIT_MS`（10 分钟） |

## `buukle.agent.controller-log`

| 键 | 默认值 | 说明 |
|---|---|---|
| `max-param-length` | `200` | 请求参数日志截断长度 |
| `max-result-length` | `300` | 响应结果日志截断长度 |
| `full-prefixes` | `/api/v1/api` | 命中这些前缀的请求参数与结果不打全（不截断），逗号分隔 |

## `buukle.agent.mcp`

| 键 | 默认值 | 说明 |
|---|---|---|
| `connect-timeout` | `30s` | MCP 连接超时 |
| `sse-init-timeout` | `30s` | MCP SSE 初始化超时 |
| `sse-read-timeout` | `30s` | MCP SSE 读取超时 |
| `rpc-timeout` | `30s` | MCP RPC 超时 |
| `detection-timeout` | `10s` | MCP 服务发现超时 |
| `direct-read-timeout` | `30s` | MCP 直接读取超时 |
| `direct-connect-timeout` | `10s` | MCP 直接连接超时 |

## `buukle.agent.crypto`

| 键 | 默认值 | 说明 |
|---|---|---|
| `aes-key` | `""` | 加密存储密钥所用的 AES Key |

## `buukle.agent.lock.capability`

| 键 | 默认值 | 说明 |
|---|---|---|
| `wait-time` | `5s` | 获取锁等待超时 |
| `lease-time` | `10s` | 锁持有时间 |

## `buukle.agent.audit`

| 键 | 默认值 | 说明 |
|---|---|---|
| `frontend-retention-days` | `7` | 前端事件保留天数 |
| `backend-retention-days` | `90` | 后端事件保留天数 |
| `cleanup-cron` | `0 0 3 * * ?` | 清理定时任务 cron |

## `buukle.agent.distributed`

| 键 | 默认值 | 说明 |
|---|---|---|
| `owner-lease` | `5m` | session 执行者 owner 租约（多副本接管） |
| `orphan-sweep-interval` | `30s` | 孤儿 run 清扫间隔（owner 失联 → run FAILED → 重新唤醒） |

## `buukle.agent.delegate`

`application.yml` 中未出现（走类内默认值）。此处为完整性列出。

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | delegate 子 Agent 机制开关（可一键回退） |
| `max-nested-depth` | `3` | 子 Agent 最大嵌套深度 |
| `max-sub-loop-count` | `8` | 子 Agent 循环最大轮次 |
| `execution-timeout` | `10m` | 单个子 Agent 执行总超时（外层工具 fiber 需容得下该预算） |
| `max-prompt-chars` | `20000` | 渲染后 prompt 最大长度 |
| `max-result-chars` | `4000` | 子 Agent 返回结果最大字符数 |
| `max-parallel` | `4` | DAG 层内最大并行数 |
| `max-dag-tasks` | `16` | DAG 任务数上限 |
| `max-dag-retries` | `1` | 缺失/UNCERTAIN 泳道最多重派发轮数 |
| `max-envelope-chars` | `8000` | 信封上游预算（字节）：超限的上游 value 会被截断并标记，信封本身永不整体丢弃 |

## `hri-ai`

| 键 | 默认值 | 说明 |
|---|---|---|
| `completions.call-timeout` | `60s` | 单次 completions 调用超时 |
| `tasks.poll-interval` | `2s` | 任务状态轮询间隔 |
| `tasks.max-poll-seconds` | `3600` | 调用方未传 `taskTimeoutSeconds` 时的兜底任务超时 |
| `tasks.min-timeout-seconds` | `10800` | 任务超时**下限**（3h）；调用方传值低于它会抬到下限。`0` 表示不启用。见 README §5.2 |
| `tasks.callback.max-attempts` | `3` | 任务终态回调最大尝试次数 |
| `tasks.callback.initial-delay` | `2s` | 回调重试初始间隔（每次翻倍） |

`tasks.min-timeout-seconds` 与 `tasks.max-poll-seconds` 通过同一个 chokepoint（`effectiveTimeoutSeconds`）解析，该值同时决定任务级 MCP 凭证 TTL（`超时 + 15 分钟`），因此凭证绝不会早于它所属的任务过期。

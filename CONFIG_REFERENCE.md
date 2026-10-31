# Configuration Reference

Complete reference for AgentSphere's own configuration keys — `buukle.agent.*` and `hri-ai.*`. All keys live in `agent-sphere/agent-sphere-bootstrap/src/main/resources/application.yml`; the defaults below are the values shipped there (or the class default when the key is absent).

Spring-standard blocks (`spring.*`, `logging.*`, `mybatis-plus.*`, `server.*`) are intentionally not listed.

> Environment overrides: `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT` are applied on top of `spring.datasource.*` / `spring.redis.*`. In k3s they come from the `backend-config` ConfigMap (`k8s/04-backend-config.yaml`).

## `buukle.agent.session`

| Key | Default | Description |
|---|---|---|
| `idle-timeout` | `30m` | Session idle timeout |
| `max-concurrent-runs` | `10` | Max concurrent executions |
| `cache-ttl` | `60m` | Runtime context cache TTL |
| `cleanup-cron` | `0 30 4 * * ?` | Historical session cleanup cron (off-peak) |
| `cleanup-batch-size` | `200` | Sessions processed per cleanup batch |
| `cleanup-max-batches` | `50` | Max batches per cleanup run |
| `cleanup-batch-sleep-ms` | `200` | Sleep between cleanup batches, yielding to autovacuum |

Retention days and the kill switch for cleanup live in `agent_system_config` (`config_group='session'`), not here — they can be changed without a redeploy. See README §4.14.

## `buukle.agent.step`

| Key | Default | Description |
|---|---|---|
| `max-retries` | `3` | Step max retries |
| `execution-timeout` | `5m` | Step execution timeout |

## `buukle.agent.sse`

| Key | Default | Description |
|---|---|---|
| `buffer-size` | `1024` | SSE buffer size (bytes) |
| `cache-ttl` | `30s` | SSE event cache TTL |
| `heartbeat-interval` | `15s` | SSE heartbeat interval |

## `buukle.agent.chrome`

| Key | Default | Description |
|---|---|---|
| `extension-token` | `""` | Shared token the Chrome extension presents to the bridge |

## `buukle.agent.llm`

| Key | Default | Description |
|---|---|---|
| `log-body-max-length` | `128` | Request-body log truncation length |
| `connect-timeout` | `30s` | LLM API connect timeout |
| `read-timeout` | `60s` | LLM API read timeout |
| `stream-read-timeout` | `120s` | Streaming read timeout |
| `stream-timeout` | `600s` | Total streaming timeout (including reasoning) |
| `stream-max-retries` | `2` | Retries on a transient stream failure (chunks received = 0) |
| `stream-retry-delay` | `1s` | Fixed backoff for stream retries |

## `buukle.agent.runner`

| Key | Default | Description |
|---|---|---|
| `max-loop-count` | `128` | Max loop count per run (the `CHAT` source; see README §3.1a) |
| `max-silent-turns` | `2` | Consecutive tool-free turns tolerated after tool use before strict runs fail |
| `task-max-loop-count` | `2000` | Higher limit for task-triggered runs (the `TASK` source) |
| `max-reasoning-chars` | `500000` | Max persisted reasoning characters per run |
| `turn-timeout` | `660s` | Single LLM turn timeout (must exceed `stream-timeout`) |
| `llm-turn-max-retries` | `3` | Per-turn retries on a transient failure, exponential backoff 1s→2s→4s (README §3.1b) |
| `max-screenshots-per-run` | `20` | Max screenshot observations injected per run (token guard) |
| `live-tool-result-max-chars` | `4000` | Per-message cap for live non-browser tool results |
| `live-tool-result-budget-chars` | `50000` | Total live non-browser tool-result budget; older results become `read_tool_result` references |
| `live-tool-result-keep-recent` | `2` | Recent non-browser tool results always kept |
| `live-browser-full-max-chars` | `20000` | Browser results up to this size are kept full; larger pages become `read_tool_result` references |
| `compaction.budget-ratio` | `0.7` | Fallback compaction threshold (ratio of max input) when the merged sampling config has no output-token cap; with a cap the budget is `max_input - output_cap - margin` |
| `compaction.target-ratio` | `0.55` | Post-compaction target (messages + tools + fixed) |
| `compaction.adaptive-target-enabled` | `true` | Raise target when fixed overhead + tail exceeds the configured target |
| `compaction.min-tail-tokens` | `2000` | Minimum tail reserve used by adaptive target |
| `compaction.input-ratio` | `0.35` | Compaction input budget ratio |
| `compaction.tail-ratio` | `0.25` | Run-local tail budget ratio |
| `compaction.summary-max-tokens` | `4000` | Absolute summary cap |
| `compaction.summary-max-ratio` | `0.12` | Summary cap as a ratio of max input (route-aware) |
| `compaction.cooldown-turns` | `4` | Minimum turns between run-local compactions (hard ceiling overrides) |
| `compaction.max-per-run` | `12` | Max run-local compactions per run |

Instance-level `agent_instance.max_loop_count` overrides all of these for that instance (`INSTANCE` source, highest priority except a pinned value). See README §3.1a for the full resolution order.

## `buukle.agent.tool`

| Key | Default | Description |
|---|---|---|
| `max-parallel` | `3` | Max parallel tool executions |
| `submit-timeout` | `30s` | Tool submission timeout |
| `execution-timeout` | `60s` | Single batch tool execution timeout |
| `cli-timeout` | `30s` | CLI tool timeout |

### `tool.web-read` / `tool.web-fetch`

| Key | Default | Description |
|---|---|---|
| `connect-timeout` | `10s` | Connect timeout |
| `read-timeout` | `30s` | Read timeout |

### `tool.web-read-advanced`

| Key | Default | Description |
|---|---|---|
| `use-jina-fallback` | `true` | Enable Jina Reader fallback |
| `jina-reader-url` | `https://r.jina.ai/` | Jina Reader service URL |
| `jina-api-key` | `""` | Jina API key |
| `jina-engine` | `direct` | Jina engine mode (`direct` / `browser`) |
| `jina-connect-timeout` | `30` | Jina connect timeout (seconds) |
| `jina-read-timeout` | `30` | Jina read timeout (seconds) |
| `jina-timeout-buffer` | `10` | Jina timeout buffer (seconds) |
| `html-to-markdown.max-content-length` | `2097152` | Max HTML→Markdown length (bytes) |
| `html-to-markdown.exclude-tags` | `script,style,noscript,meta,link,header,footer,nav,iframe,embed,object` | Tags dropped during HTML→Markdown conversion |

### `tool.web-fetch-advanced`

| Key | Default | Description |
|---|---|---|
| `max-retries` | `3` | Max retries |
| `retry-backoff-multiplier` | `2` | Backoff multiplier |
| `retry-delay-ms` | `1000` | Initial retry delay (ms) |
| `max-redirects` | `5` | Max redirects |
| `max-response-bytes` | `5242880` | Max response body (bytes) |

### `tool.js-render`

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable JS rendering |
| `max-wait-millis` | `10000` | Max wait time (ms) |
| `background-js-timeout` | `3000` | Background JS timeout (ms) |
| `max-pool-size` | `3` | Browser pool max size |

## `buukle.agent.wait`

Bounds and retry limits for the `wait` tool are code constants (`WaitConstants`), not config: `MIN_WAIT_MS = 100`, `MAX_WAIT_MS = 600000`, `MAX_RESUME_ATTEMPTS = 5`. See README §3.8a.

| Key | Default | Description |
|---|---|---|
| `wake-cron` | `*/10 * * * * ?` | Wake compensation sweep. The primary path is one in-memory timer per pending row; a replica restart loses them all, so this layer falls back to `wake_at` (a CAS guarantees no double resume) |
| `reconcile-interval` | `PT60S` | Stuck-`WAITING` reconcile interval |
| `reconcile-stale-minutes` | `15` | Threshold (minutes) for considering a `WAITING` run stuck; must be greater than `MAX_WAIT_MS` (10 min) |

## `buukle.agent.controller-log`

| Key | Default | Description |
|---|---|---|
| `max-param-length` | `200` | Request param log truncation |
| `max-result-length` | `300` | Response result log truncation |
| `full-prefixes` | `/api/v1/api` | Comma-separated path prefixes logged in full (no truncation) |

## `buukle.agent.mcp`

| Key | Default | Description |
|---|---|---|
| `connect-timeout` | `30s` | MCP connect timeout |
| `sse-init-timeout` | `30s` | MCP SSE init timeout |
| `sse-read-timeout` | `30s` | MCP SSE read timeout |
| `rpc-timeout` | `30s` | MCP RPC timeout |
| `detection-timeout` | `10s` | MCP service detection timeout |
| `direct-read-timeout` | `30s` | MCP direct read timeout |
| `direct-connect-timeout` | `10s` | MCP direct connect timeout |

## `buukle.agent.crypto`

| Key | Default | Description |
|---|---|---|
| `aes-key` | `""` | AES key used to encrypt stored secrets |

## `buukle.agent.lock.capability`

| Key | Default | Description |
|---|---|---|
| `wait-time` | `5s` | Lock acquire wait timeout |
| `lease-time` | `10s` | Lock lease duration |

## `buukle.agent.audit`

| Key | Default | Description |
|---|---|---|
| `frontend-retention-days` | `7` | Frontend event retention |
| `backend-retention-days` | `90` | Backend event retention |
| `cleanup-cron` | `0 0 3 * * ?` | Cleanup cron expression |

## `buukle.agent.distributed`

| Key | Default | Description |
|---|---|---|
| `owner-lease` | `5m` | Session executor owner lease (multi-replica takeover) |
| `orphan-sweep-interval` | `30s` | Orphan-run sweep interval (stale owner → run FAILED → re-wake) |

## `buukle.agent.run`

| Key | Default | Description |
|---|---|---|
| `stale-reconcile-minutes` | `30` | DB-level safety net: RUNNING run untouched longer than this with no session owner → FAILED (`StaleRunningRunReconciler`) |
| `stale-reconcile-interval` | `PT1M` | Sweep interval for the stale-RUNNING reconciler |

## `buukle.agent.delegate`

Not present in `application.yml` (class defaults apply). Listed here for completeness.

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Feature flag for the delegate sub-agent mechanism |
| `max-nested-depth` | `3` | Max sub-agent nesting depth |
| `max-sub-loop-count` | `8` | Max loop count for a sub-agent loop |
| `execution-timeout` | `10m` | Total timeout for one sub-agent execution (the outer tool fiber must accommodate it) |
| `max-prompt-chars` | `20000` | Max rendered prompt length |
| `max-result-chars` | `4000` | Max characters a sub-agent may return |
| `max-parallel` | `4` | Max parallelism within a DAG layer |
| `max-dag-tasks` | `16` | Upper bound on DAG task count |
| `max-dag-retries` | `1` | Max re-dispatch rounds for missing/UNCERTAIN lanes |
| `max-envelope-chars` | `8000` | Envelope upstream budget (bytes); over-limit values are truncated and marked, the envelope itself is never dropped wholesale |

## `hri-ai`

| Key | Default | Description |
|---|---|---|
| `completions.call-timeout` | `60s` | Single completions call timeout |
| `tasks.poll-interval` | `2s` | Task status poll interval |
| `tasks.max-poll-seconds` | `3600` | Fallback task timeout when the caller omits `taskTimeoutSeconds` |
| `tasks.min-timeout-seconds` | `10800` | Task timeout **floor** (3h); a caller-supplied value below it is raised. `0` disables the floor. See README §5.2 |
| `tasks.callback.max-attempts` | `3` | Max attempts for the terminal task callback |
| `tasks.callback.initial-delay` | `2s` | Callback retry initial delay (doubles per attempt) |

`tasks.min-timeout-seconds` and `tasks.max-poll-seconds` resolve through a single chokepoint (`effectiveTimeoutSeconds`) that also sets the task-level MCP credential TTL (`timeout + 15 min`), so the credential can never expire before its task.

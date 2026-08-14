# 多副本后端升级方案（Redis 事件总线 + 运行态整体迁移）

> 目标：消除后端对「单副本」的隐含假设，使 `k8s/05-backend.yaml` 的 `replicas` 可安全扩展到 2+。
> 覆盖范围：`agent-sphere/`（Spring Boot 后端）。

## 决策锁定

- 跳过 LB 粘性会话缓解，直接做 **Redis 事件总线 + 运行态整体迁移**。
- run 执行锚定单一副本（协调层全 Redis，in-flight loop 瞬态留在执行副本内）。
- 崩溃语义：**方案 A** —— owner 租约 → 孤儿清扫置 run `FAILED` → 状态复位 → 续跑 Redis 队列（不做中途 checkpoint）。
- `AuditLogService` 前端限流入 Redis（本次一并做）。
- 事件投递走 Redis pub/sub **单路径**（含发布者自身），无重复投递；SSE 事件缓存入 Redis，客户端跨副本重连可回放。

## 一、现状不兼容位置（排查结论）

### P0 — 事件流与 run 运行态全部进程内

| 位置 | 问题 |
|---|---|
| `runtime/.../sse/SseManager.java:22-25` | SSE emitter + 事件缓存都在进程内，事件只能投给**本副本**上的连接。 |
| `agui/.../AguiStreamManager.java:23` | AG-UI 的 SSE emitter 同样进程内。 |
| `runtime-kernel/.../SessionRunCoordinator.java:22-23` | 会话运行状态机 + 执行线程池在进程内。 |
| `runtime-kernel/.../SessionRunner.java:58-59,75-76` | `CANCELLED_RUNS/CANCELLED_SESSIONS` 是 `static` JVM 级集合；`contexts/pendingRunIds` 实例级。**stop 请求落到别的副本无效**。 |
| `runtime-kernel/.../runner/SessionInputManager.java:15-16` | steering/queue 输入在进程内。 |
| `runtime-orchestration/.../RuntimeEventListener.java:38` | reasoning 累积 buffer 进程内，仅执行副本落库。 |
| `agui/.../AguiEventTranslator.java:38` | AG-UI run 流状态进程内。 |

机制：`ChatRuntimeService.startRun` → `RuntimeOrchestrator`(异步) → 同一副本执行 `SessionRunner` → 事件经 `ApplicationEventPublisher` → `RuntimeEventListener` → `SseManager` 本副本投递。**浏览器 SSE 连接与 run 请求必须落在同一副本才正常**。

### P1 — Chrome 桥跨副本回调必然失败

- `common/chrome/ChromePendingStore.java:8` 是 static 内存 Map：工具在**执行副本** `put(commandId, future)`（`CapabilityBuiltinToolChrome.java:120`），插件回调 `POST /api/v1/chrome/callback`（`ChromeCallbackController.java:29-37`）经 LB 落到**任意副本** → `contains` 为 false → 403，30s 超时。
- 指令下发 `SseManager.sendByUser`（`RuntimeEventListener.java:163`）依赖插件用户级 SSE 与 run 同副本。

### P1 — 任务轮询单副本故障即中断

- `tasks/.../AgentTaskServiceImpl.java:98-101,382-430`：`pollFutures/pollPhases` 进程内。副本重启后任务永远停在 `RUNNING`；`stop()` 只取消本地 poller；execute→extract→refine 阶段切换也在进程内。

### P2 — 定时任务每副本重复执行

- `infrastructure/.../AuditLogCleanupTask.java:23` `@Scheduled`，每个副本都在 3:00 清一次（幂等但浪费 + 日志噪音）。

### P2 — 系统配置缓存与 AES 密钥竞态（数据完整性）

- `infrastructure/.../SystemConfigServiceImpl.java:26-29,52-58`：Caffeine 5 分钟本地缓存，`set/invalidateCache` 只影响本副本 → 其他副本最长 5 分钟脏读。
- **AES 密钥首启竞态**：生成点实际有**两处**——`get()`（`SystemConfigServiceImpl.java:53`，缓存 miss 且 DB 值为空时）与 `@PostConstruct init()`（:89-90，内部同样调 `get`）。多副本同时首启/同时缓存 miss 时各生成一把不同密钥并各自缓存 → API key / SSO client_secret 用 A 副本密钥加密，B 副本解不开（`CryptoService.java`）。修复需**统一走 Redis `SET NX` 原子占位**，两处生成路径都覆盖（见 4.4 item 17）。

### P3 — 低危

- `instance/.../AuditLogService.java:30,71-80`：`frontendRateMap` 内存限流，多副本放大 N 倍（另有 `RateLimitInterceptor` 用 Redisson，正确）。
- `mcp/.../McpTransportFactory.java:19`：每副本独立 MCP SSE 长连，某些单会话 MCP 服务器会冲突（本次不改，记录即可）。
- `sso/.../SsoServiceImpl.java:226-239`：新用户资源初始化用虚拟线程，并发首登可能重复开通（低危，可后续加固）。

### 已验证兼容（无需改动）

- 鉴权 token：DB 存储 + Redis 缓存（`AuthInterceptor.java:56-66`）
- SSO state/OTC：Redisson `RBucket`（`SsoServiceImpl.java:307-319`）
- `RateLimitInterceptor`、`InstanceCapabilityServiceImpl`（Redisson 锁）
- Flyway（DB 锁保护）、无本地文件存储

## 二、架构设计

1. **事件分发**：Redis pub/sub（Redisson `RTopic`）作为唯一投递路径。`RuntimeEventListener`/`AguiEventTranslator` 只在**执行副本**做 DB 副作用与 AG-UI 翻译，然后**发布**到 topic；每个副本（含发布者自己）的 relay 订阅后只投给本地持有的 SSE emitter。单路径投递，无重复。
2. **运行态协调**：`SessionRunCoordinator` 状态机、`SessionInputManager` 队列/steer、`SessionRunner` 上下文/pendingRun/取消集合全部落 Redis，副本可互换。
3. **in-flight loop 状态留在执行副本**：`SessionRunner.run()` 循环内瞬态（`messages`、LLM 流、工具结果）不迁出；协调层分布式后副本宕机只丢失当次 run（与单副本 JVM 崩溃行为一致），但输入队列、任务状态、取消语义存活，可被接管。
4. **崩溃自愈**：每 session 执行者通过 Redis 租约（owner + TTL）标记；孤儿清扫任务发现租约过期 → 孤儿 run 置 `FAILED`、状态复位、续跑队列中剩余输入。

## 三、Redis 键空间与 Topic 设计

Topic（常量集中放 `DistributedRuntimeConstants`，遵循无魔法值规范）：

| Topic | 载荷 | 消费者 |
|---|---|---|
| `runtime.events` | 已 transform 的 `RuntimeEventVO`（session 级前端事件） | 各副本 → 本地 `SseManager.sendBySession` |
| `runtime.agui` | 翻译后的 `AguiEventVO` + `{sessionId, runId, terminal}` | 各副本 → 本地 `AguiStreamManager.send/complete` |
| `runtime.chrome.command` | `{username, ChromeCommandDTO}` | 各副本 → 本地 `SseManager.sendByUser` |
| `runtime.chrome.callback` | `ChromeCallbackDTO` | 各副本 → 本地 `ChromePendingStore.complete` |

键（统一前缀 `runtime:`）：

| 键 | 类型 | 用途 |
|---|---|---|
| `runtime:lock:{sessionId}` | RLock | 会话状态机互斥 |
| `runtime:state:{sessionId}` | String | IDLE / RUNNING / RUNNING_WITH_PENDING |
| `runtime:owner:{sessionId}` | String+TTL | `{replicaId, leaseUntil}` 执行者租约 |
| `runtime:pending-run:{sessionId}` | String | 当前 runId |
| `runtime:ctx:{sessionId}` | RMapCache 条目(TTL 30m) | 序列化 `KernelContext` |
| `runtime:queue:{sessionId}` | List | 排队输入 `InputMessage` |
| `runtime:steer:{sessionId}` | String | steer 槽位（last-wins） |
| `runtime:cancel-run:{runId}` | RSet | run 级取消集合（任意副本 `cancelRun` 写入；执行副本 loop 读取并消费） |
| `runtime:cancel-session:{sessionId}` | RSet | session 级取消集合（任意副本 `stopSession` 写入；loop 读取并消费） |
| `runtime:event-cache:{sessionId}` | List+TTL(30s) | session 级 SSE 事件缓存 |
| `runtime:event-cache-user:{username}` | List+TTL(30s) | 用户级 SSE 事件缓存 |

要点：
- 事件缓存入 Redis 后，客户端**跨副本重连**（`/stream/{sessionId}`、`/user/task/stream`、AG-UI `/connect`）可回放。
- 事件缓存**单写者**（origin 副本）写、多读者 flush，避免重复。**顺序约定：发布方先写 Redis 事件缓存、再发布 topic**；relay 只投本地 emitter，不重写缓存 → 重连回放不丢、无重复。
- 序列化：`RuntimeEventVO.eventType` 是 sealed interface（`@JsonValue`）。**实现时新增 `EventTypeDeserializer`**（按 `value()` 字符串映射到具体 enum），`RedisEventBus` 的 `JsonJacksonCodec` 注册该模块；`RuntimeEventVO/RuntimeEventDataVO/AguiEventVO/ChromeCommandDTO/ChromeCallbackDTO` 均做 round-trip 单测。
- **cancel 集合读频率约束**：`SessionRunner` 的取消检查点（loop 每轮、LLM 流等待每 1s、`FiberSet.awaitAll` 取消回调）直读 `RSet`；检查点天然低频（≤ 每轮 / 1s），**禁止在每 token 热路径读 Redis**。首版直读即可，后续如需要可加本地镜像 + topic 失效。
- **cancel 消费语义**：`runtime:cancel-session` 由执行副本 loop 收口 `remove`；`stopSession` 发现该 session **无活动 run 时立即 `remove`**（防标志残留误杀之后新发起的 run）。`runtime:cancel-run` 同理。

## 四、改造清单（按模块）

### 4.1 基础设施（agent-sphere-infrastructure）

1. `RedisConfig.java` — 新增第二个 `RedissonClient` bean（`JsonJacksonCodec`）专供事件总线；现有默认 codec 客户端不动（避免影响 `CacheService`/锁/`RBucket`）。
2. 新增 `eventbus/RedisEventBus.java` — `publish(topic, payload)` / `subscribe(topic, consumer)` 通用封装；`EventTypeCodec`（sealed interface `EventType` ↔ 字符串）。
3. 新增 `eventbus/DistributedRuntimeConstants.java` — topic/key 前缀常量。

### 4.2 事件投递链路（runtime-orchestration / agui / common）

4. `RuntimeEventListener.onEvent` — DB 副作用保留在本地（run 状态、工具记录、**reasoning 累积/flush 不跨副本迁移**，仅执行副本在终态 flush；孤儿清扫置 FAILED 时不重复 flush）；末尾本地 `sendBySession` 改为**先写 session 事件缓存、再发布** `transformForFrontend(event)` 到 `runtime.events`；`handleChromeCommand` 改发布 `{username, command}` 到 `runtime.chrome.command`。
5. 新增 `relay/SseEventRelay.java` — 订阅 `runtime.events` → `sseManager.sendBySession`；`runtime.chrome.command` → `sseManager.sendByUser`。
6. `SseManager` — `sessionEmitters/userEmitters` 保持本地；`eventCache/userEventCache` 迁 Redis List+TTL(30s)，单写者（origin 副本）写、多读者 flush。
7. `AguiEventTranslator.onRuntimeEvent` — 翻译与 `states` 留执行副本（累积状态不可跨副本重建），输出改为发布 `{sessionId, runId, AguiEventVO, terminal}` 到 `runtime.agui`。
8. 新增 `relay/AguiEventRelay.java` — 订阅 `runtime.agui` → 本地 `AguiStreamManager.send/complete`。
9. `ChromeCallbackController.callback` — 去掉 `ChromePendingStore.contains` 本地门禁（跨副本必然 false），校验 `X-Extension-Token` 后发布 `ChromeCallbackDTO` 到 `runtime.chrome.callback`，返回 ok；超时兜底仍由工具侧 `future.get(30s)` 负责。
10. 新增 `relay/ChromeCallbackRelay.java` — 订阅 → 本地 `ChromePendingStore.complete`。`CapabilityBuiltinToolChrome` 与插件协议零改动。

### 4.3 运行态分布式（runtime-kernel）

11. `SessionRunCoordinator` — `states` → Redis String + `RLock`；`wake()`：存 pendingRun + ctx → 抢锁 → 读 state → IDLE 置 RUNNING、提交本地 executor 执行 `sessionRunner.run(sessionId)`；RUNNING 有 pending 置 RUNNING_WITH_PENDING。finally：抢锁，`hasPending` 续跑，否则置 IDLE 并清理 owner。执行前设 owner 租约（TTL ~5min），执行中每 60s 续约。
12. `SessionInputManager` — `steerSlots/queues` → Redis `runtime:steer`(String last-wins) / `runtime:queue`(List)；`InputMessage` 四标量字段 JSON 序列化；`steer/queue/promoteInput/hasPending/hasQueued/clear` 对应 Redis 操作。
13. `SessionRunner` — `contexts` → `RMapCache<Long, KernelContext>`（TTL 30m，对应现 `CONTEXT_TTL`），`run()` 开头从 Redis 读 ctx（缺失则 null，现有逻辑已容忍）；`pendingRunIds` → `runtime:pending-run:{sessionId}`；`CANCELLED_RUNS/SESSIONS`（static）→ `RSet`，取消检查点（loop 每轮、LLM 流等待每 1s、FiberSet 取消回调）直读 Redis；**loop 收口处 `remove` 消费（run 级 / session 级任一命中即置 `CANCELLED` + 发布取消终态）**；`contextSweeper` 删除（条目 TTL 替代）；`hasActiveRun` 查 `runtime:state`/owner。
14. 新增 `relay/OrphanRunSweeper.java` — @Scheduled（各副本运行，间隔可配）：扫描 RUNNING 会话，owner 租约过期 → 抢锁 → `runSpi` 将该 run 置 `FAILED` → 状态复位 → re-wake（续跑队列）。**职责边界：sweeper 只处理 SessionRunner 的 run 终态；任务级推进由 4.4 item 15 的 task poller 负责，二者不双写同一 run。**
14b. **分布式 stop 语义**（对接 session 级 stop，`stopRun/stopSession` 任意副本可调，无需改签名）：
    - `stopRun(sessionId, runId)`：`cancelRun(runId)` → 写 `runtime:cancel-run:{runId}`；运行中 run 由执行副本 loop 读取终止。
    - `stopSession(sessionId)`：`findActiveRun`(DB) 定位活动 run + `cancelSession` 写 `runtime:cancel-session:{sessionId}`。运行中（RUNNING/PENDING）由 loop 终止；**AWAITING_USER 停车态 loop 已退出、标志不生效 → stopSession 显式复用澄清取消逻辑（等同现有 `stopRun` 的 AWAITING_USER 分支）后 `remove` 标志**；无活动 run 时立即 `remove`（防误杀后续新 run）。

### 4.4 弹性与一致性（tasks / infrastructure）

15. `AgentTaskServiceImpl` — 删 `pollFutures/pollPhases` 内存调度；新增每副本 @Scheduled sweep（间隔复用 `hri-ai.tasks.poll-interval`，默认 2s）：`SELECT ... FOR UPDATE SKIP LOCKED` 认领 `STATUS=RUNNING` 且 `polled_at < now-interval` 的任务（**存量 RUNNING 任务 `polled_at` 为 NULL → 认领条件含 `polled_at IS NULL`，首轮立即认领**），执行一轮 `pollOnce(taskId)` 并更新 `polled_at`；**poller 执行前 `AuthContext.setUsername(task.getCreatedBy())`（沿用 `fireFollowUpRun` 的上下文注入，否则归属校验失败），finally 清理**；终态转换改条件更新保证单胜者，`notifyTerminal` 只触发一次；`stop()` 条件更新置 CANCELLED，无需取消本地 future；超时兜底用新列 `started_at`；`poll_phase` 标记 execute/extract/refine 阶段防重复推进。
16. `AuditLogCleanupTask` — `cleanup()` 用 Redisson `RLock("scheduler:audit-cleanup")` `tryLock` 包裹，未获锁直接跳过。
17. `SystemConfigServiceImpl` — Caffeine → Redis 缓存（`RBucket`+5min TTL）；`set/invalidateCache` 直接更新/删除 Redis 键，全局一致；`AES_KEY` 生成点有**两处**（`get()` 缓存 miss 时、`@PostConstruct init()`），统一收敛：用 Redis `SET NX` 原子占位 `crypto:aes-key:init`，仅占位成功者生成并落库，其余副本回读 DB。
18. `AuditLogService` — `frontendRateMap` → Redis `INCR`+`EXPIRE`（10s 窗口）。

## 五、DB 迁移

`V43__add_task_polling_columns.sql`（当前最高 V42）：

```sql
ALTER TABLE agent_task
    ADD COLUMN poll_phase VARCHAR(20) NULL,
    ADD COLUMN polled_at  TIMESTAMP   NULL,
    ADD COLUMN started_at TIMESTAMP   NULL;
```

同步 `AgentTask` 实体字段（遵循 AGENTS.md 实体规范：`@TableField` 等）。

## 六、配置与部署

19. `application.yml` / `AgentRuntimeProperties` — 新增 `DistributedConfig` 嵌套类（沿用 `buukle.agent` 前缀风格）：
    ```yaml
    buukle:
      agent:
        distributed:
          owner-lease: 5m              # owner 租约时长
          orphan-sweep-interval: 30s   # 孤儿清扫间隔
          event-cache-ttl: 30s         # 事件缓存 TTL
    ```
20. `k8s/05-backend.yaml` — `replicas: 2` + `livenessProbe httpGet /actuator/health`（actuator 已在 bootstrap 依赖，readiness tcpSocket 保留）；`k8s/07-ingress.yaml` **无需粘性会话**（事件走广播，SSE 可跨副本重连）。

## 七、实现顺序（里程碑）

1. **M1 基础设施 + 事件总线**：`RedisEventBus` / `EventTypeCodec` / topic 常量 + 序列化 round-trip 单测。
2. **M2 事件投递链路**：`SseManager` 缓存入 Redis、`RuntimeEventListener` 发布、`SseEventRelay` / `AguiEventRelay` / `ChromeCallbackRelay`、`ChromeCallbackController` 广播。SSE 跨副本投递 + Chrome 回调即修复。
3. **M3 运行态分布式**：`SessionRunCoordinator`（锁+state+lease）、`SessionInputManager`、`SessionRunner`（ctx/pendingRun/RSet 取消）、`OrphanRunSweeper`。
4. **M4 弹性与一致性**：任务轮询 DB 化、`AuditLogCleanupTask` 锁、`SystemConfigServiceImpl`/AES 修复、`AuditLogService` 限流入 Redis（M4 内部各组件独立，可并行）。
5. **M5 收尾**：`application.yml`、`k8s` replicas=2、全量回归。

## 八、测试与验证

- **单测**：`mvn test`。新增：`KernelContext` / `RuntimeEventVO` 线格式 / `InputMessage` 序列化 round-trip；`SessionRunCoordinator` 单副本语义回归。现有 `SessionControllerTest`、`AgentConversationWorkflowTest`、`AgentTaskServiceTest` 保持通过。
- **本地双实例冒烟**（无 CI，需手动）：同 PG+Redis 起 8080/8081 两实例，验证：
  1. `POST /chat` 发到 A、SSE 连到 B，事件完整不丢；
  2. 插件命令发出后回调落在另一实例，仍能在 30s 内完成；
  3. B 起 run 后调 A 的 **stop（运行中 + AWAITING_USER 停车态）/ steer / 澄清应答 resume** 生效，run 随 cancel 集合跨副本迁移；
  4. 提交 task 后 kill 执行副本，任务 ≤一个轮询周期内被接管完成；
  5. 多副本重启后 API key / SSO client_secret 加解密一致；
  6. 断连后 SSE **跨副本重连**回放 Redis 缓存事件（`/stream/{sessionId}`、`/user/task/stream`、AG-UI `/connect`）。
- **k8s**：`replicas: 2` 灰度观察，回滚为 1 即恢复单副本行为。

## 九、风险与回滚

- **in-flight run 宕机丢失**：决策 A 接受（等价单副本 JVM 崩溃），且新增自愈（孤儿清扫 + 队列续跑）；不做中途 checkpoint（`SessionRunner` 循环状态持久化成本接近重写）。
- **Redis 成为强依赖**：事件投递全走 pub/sub；可用性等价现有 DB/Redis 依赖。topic 订阅断连（Redisson 自动重连）期间事件丢失风险与现状 SSE 断连一致，靠事件缓存/回放兜底。
- **每里程碑独立可上线、可单独回滚**：M1–M2 纯增量（单副本行为不变），M3/M4 各自独立。

## 附：关键设计备忘

- `EventType` 为 sealed interface（`@JsonValue`），`runtime.events` 载荷采用**自定义 `EventTypeDeserializer`**（按 `value()` 字符串映射到具体 enum），`RedisEventBus` 的 `JsonJacksonCodec` 注册该模块（见 §三 line 98）。
- `AguiEventTranslator.states` 是累积翻译状态，必须留在执行副本，topic 载荷为**翻译后**的 `AguiEventVO`。
- `KernelContext` 用 Redisson `RMapCache` 存 JSON（`JsonJacksonCodec`），需保证其字段 VO 可 Jackson 序列化（实现时逐字段审计，排除不可序列化成员如工具实例/线程绑定资源，并加 round-trip 测试）。
- `SessionRunner.cancelRun/cancelSession` 是 static 方法，改 Redis 后仍可被任意副本调用（`ChatRuntimeService.stopSession/stopRun` 不经改）。

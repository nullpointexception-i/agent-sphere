# AgentSphere i18n Glossary

This glossary is the single source of truth for translating Chinese comments, developer-facing
defaults and model-facing descriptions into English. Keep identifiers, API paths, SQL identifiers,
configuration keys, tool names, JSON fields and product names unchanged.

| Chinese | English | Notes |
|---|---|---|
| 实例 | instance | Agent configuration unit |
| 会话 | session | Conversation / execution context |
| 运行 | run | One execution unit |
| 时间线 | timeline | User-visible execution trace |
| 编排 | orchestration | Runtime orchestration layer |
| 内核 | kernel | ReAct runtime kernel |
| 挂起 | suspend | A run parked in WAITING / AWAITING_USER |
| 唤醒 | wake / resume | Wake a suspended run |
| 收口 | finalize / finish | End an agentic run through `finish` |
| 兜底 | fallback | Compensation or default path |
| 幂等 | idempotent | Retry-safe behavior |
| 租户 | tenant | Tenant boundary |
| 行级权限 | row-level permission | `created_by` data scope |
| 逻辑删除 | logical delete | `delete_flag` |
| 硬删 | hard delete | Physical delete used by session cleanup |
| 派发 | dispatch | Group Router -> Agent Router -> Worker |
| 任务板 | work item board | Super-Agent work items |
| 数字员工 | digital employee / Super-Agent | Product concept |
| 数字分身 | digital avatar | C-end label for a Super-Agent |
| 群级分派 | group-level dispatch | Group Router |
| 线程 | thread | IM conversation / external thread |
| 事件 | event | Normalized inbound event |
| 投递 | delivery | Event -> agent delivery |
| 出站消息 | outbound message | Outbox row |
| 能力 | capability | MCP / Skill / CLI / Builtin |
| 技能 | skill | Reusable multi-step capability |
| 模型路由 | model route | Primary + fallback chain |
| 供应商 | provider | LLM provider |
| 故障转移 | failover | Fallback route execution |
| 上下文压缩 | context compaction | History summarization |
| 工具调用 | tool call | LLM tool invocation |
| 待办 | todo | Session todo item |
| 澄清 | clarification | Human-in-the-loop question |
| 审批 | approval | L2 tool approval |
| 只读模式 | read-only mode | `RunMode.READ_ONLY` |
| 写模式 | write mode | `RunMode.WRITE` |
| 扩展 | extension | Chrome MV3 extension |
| 嵌入组件 | widget | Embeddable chat widget |
| 截图 | screenshot | Browser screenshot observation |
| 标签页 | tab | Browser tab |
| 会话清理 | session cleanup | Hard-delete retention task |
| 保留天数 | retention days | Data retention |
| 审计日志 | audit log | `sys_audit_log` |
| 身份源 | identity provider | OIDC IdP |
| 单点登录 | SSO | OIDC authorization code + PKCE |
| 资源模板 | resource template | Per-user provisioning template |
| 技能广场 | skill hub | Public skill marketplace |
| 任务产物 | task artifact | Structured task output |
| 提示工程 | prompt engineering | Completions prompt management |
| 内置工具 | builtin tool | Java SPI tool |
| 命令行工具 | CLI tool | ProcessBuilder-backed tool |
| 文件存储 | file store | `agent_file_store` |
| 工作台 | workspace | C-end shell at `/workspace` |
| 用量 | usage | Token / cost usage |
| 终态 | terminal state | COMPLETED / FAILED / CANCELLED |
| 停车态 | parked state | WAITING / AWAITING_USER |
| 轮次预算 | loop budget | per-run `loop_count` / `loop_limit` |
| 强收口 | forced finalization | Loop cap reached |
| 现场 | on-site / in place | Context-dependent; prefer a concrete noun |
| 兜底扫描 | compensation sweep | Scheduled reconciliation |
| 对账 | reconcile | Stale-state reconciliation |
| 孤儿运行 | orphan run | Run without an owner replica |
| 租约 | lease | Redis owner / outbox lease |
| 单写者 | single writer | One replica owns the run |
| 心跳 | heartbeat | SSE keepalive |
| 断线补拉 | reconnect catch-up | `afterSeq` replay |
| 增量 | incremental delta | SSE token delta |
| 快照 | snapshot | Browser DOM / state snapshot |
| 影子 | shadow | Shadow DOM |
| 受信输入 | trusted input | CDP input |
| 无头浏览器 | headless browser | Browser automation context |

## Translation rules

1. Preserve code, identifiers, paths, URLs, JSON examples and configuration keys.
2. Keep Javadoc tags (`@param`, `@return`, `<p>`, `<ul>`) and Markdown structure.
3. Do not translate string literals in this pass; they are handled by the defaults workstream.
4. Do not edit applied Flyway migrations (`V1`...`V149`).
5. Prefer concise technical English over literal translation.
6. Keep the original meaning, including incident notes and "do not do this" warnings.
7. Use "run" for 运行, "session" for 会话, "execution" only when it means the act of executing.
8. Use "finalize" for 收口 when describing the `finish` protocol; use "finish" for the tool name.
9. Use "dispatch" for 派发, not "assign".
10. Use "fallback" for 兜底, not "bottom line".

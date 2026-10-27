package com.buukle.agent.runtime.kernel.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.spi.CapabilityBuiltinSpi;
import com.buukle.agent.capability.mcp.spi.CapabilityMcpSpi;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.instance.dtvo.dto.TodowriteResultDTO;
import com.buukle.agent.instance.dtvo.vo.SessionTodoVO;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.SessionTodoSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.ExecBindingKeys;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.contract.CliExecutionBinding;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.service.CliExecutorService;
import com.buukle.agent.runtime.kernel.runner.sub.DelegateService;
import com.buukle.agent.runtime.kernel.runner.sub.SubAgentConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CLARIFICATION_ID_LENGTH = 8;
    private static final String JSON_ERROR_UNKNOWN_TOOL = "{\"error\":\"Unknown tool: ";
    private static final String JSON_ERROR_CLARIFICATION = "{\"error\":\"Failed to process clarification\"}";
    private static final String JSON_ERROR_WAIT = "{\"error\":\"Failed to process wait\"}";
    private static final String JSON_ERROR_UNSUPPORTED_TYPE = "{\"error\":\"Unsupported capability type: ";
    private static final String JSON_ERROR_EXECUTION = "{\"error\":\"";

    private final List<CapabilityMcpSpi> mcpSpis;
    private final CapabilityBuiltinSpi builtinSpi;
    private final CliExecutorService cliExecutorService;
    private final SessionTodoSpi sessionTodoSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final ClarificationSpi clarificationSpi;
    private final PendingWaitSpi pendingWaitSpi;
    private final DelegateService delegateService;
    /** 任务级 MCP 凭证来源。 */
    private final TaskMcpCredentialStore taskMcpCredentialStore;

    /** 主循环工具入口：以根上下文执行（主 Agent 无父级限制）。 */
    public String execute(TurnToolCall tc, Long sessionId, Long runId, List<RuntimeTool> tools) {
        return execute(tc, SubRunExecutionContext.root(sessionId, runId, null), tools);
    }

    /** 带嵌套上下文执行：Skill 子循环内层工具使用子上下文（深度/栈/白名单）。 */
    public String execute(TurnToolCall tc, SubRunExecutionContext ctx, List<RuntimeTool> tools) {
        Long sessionId = ctx.getSessionId();
        Long runId = ctx.getRunId();
        RuntimeTool tool = tools.stream()
                .filter(t -> tc.name().equals(t.getLlmToolName()))
                .findFirst().orElse(null);

        if (tool == null) {
            return JSON_ERROR_UNKNOWN_TOOL + tc.name() + "\"}";
        }

        try {
            String type = tool.getCapabilityType();
            Map<String, Object> binding = tool.getExecBinding();
            String args = tc.arguments() != null ? tc.arguments() : RunnerConstants.EMPTY_JSON_ARGS;

            // delegate 统一执行入口（pseudo-tool）：main 内联或 subagent 隔离，深度由策略统一 +1
            if (SubAgentConstants.DELEGATE_TOOL.equals(tc.name())) {
                // 保持同深度不变，仅记录触发本调用的 toolCallId 与当前 owner，供嵌套子 run 回溯
                SubRunExecutionContext execCtx = ctx.child(ctx.getDepth(), ctx.getToolRefAncestry(),
                        tc.id(), ctx.getOwnerSubAgentRunId());
                return delegateService.execute(args, execCtx, tools);
            }

            if (CAPABILITY_TYPE_MCP.equals(type) && !mcpSpis.isEmpty()) {
                return mcpSpis.get(0).executeTool(
                        (String) binding.get(ExecBindingKeys.MCP_SERVER_URL),
                        (String) binding.get(ExecBindingKeys.MCP_NATIVE_TOOL_NAME), args,
                        resolveTaskMcpCredential(sessionId));
            }
            if (CAPABILITY_TYPE_BUILTIN.equals(type) && ChatClarification.CLARIFICATION_TOOL_NAME.equals(tc.name())) {
                try {
                    JsonNode argsNode = JSON.readTree(args);
                    String title = argsNode.has("title") ? argsNode.get("title").asText() : "";
                    String clarifyType = argsNode.has("type") ? argsNode.get("type").asText() : ChatClarification.CLARIFYING_DEFAULT_TYPE;
                    String optionsJson = argsNode.has("options") ? argsNode.get("options").toString() : null;
                    String clarificationId = java.util.UUID.randomUUID().toString().substring(0, CLARIFICATION_ID_LENGTH);
                    // 先建库、再发 PENDING 事件：Timeline 建澄清行时需 lookupClarificationRef 查得到记录，
                    // 否则行 refClarificationId=null，刷新后 resolveContent 取不到 response/options（只剩"已应答"）。
                    clarificationSpi.createPending(sessionId, runId, runId, title, clarifyType, optionsJson, clarificationId);
                    eventPublisher.publishEvent(new RuntimeEventVO(
                            ClarificationStatus.PENDING,
                            new RuntimeEventDataVO()
                                    .setRunId(runId)
                                    .setSessionId(sessionId)
                                    .setPrompt(title)
                                    .setType(clarifyType)
                                    .setArgumentsJson(optionsJson)
                                    .setClarificationId(clarificationId)));
                    return JSON.writeValueAsString(Map.of(
                            ChatClarification.CLARIFYING_JSON_STATUS, ChatClarification.CLARIFYING_STATUS_AWAITING_USER,
                            ChatClarification.CLARIFYING_JSON_CLARIFICATION_ID, clarificationId
                    ));
                } catch (Exception e) {
                    log.warn("Failed to process ask_clarification", e);
                    return JSON_ERROR_CLARIFICATION;
                }
            }

            // finish：正常收口在 SessionRunner 里完成（直接读入参并短路结束循环）。
            // 这里只做兜底 —— 万一有人直接执行它，返回一个明确结果而不是 "Unknown builtin tool"。
            if (CAPABILITY_TYPE_BUILTIN.equals(type) && FinishConstants.TOOL_NAME.equals(tc.name())) {
                try {
                    JsonNode argsNode = JSON.readTree(args);
                    String finishStatus = argsNode.path(FinishConstants.JSON_STATUS)
                            .asText(FinishConstants.RESULT_SUCCESS);
                    String summary = argsNode.path(FinishConstants.JSON_SUMMARY).asText("");
                    return JSON.writeValueAsString(Map.of(
                            FinishConstants.JSON_STATUS, FinishConstants.STATUS_FINISHED,
                            FinishConstants.JSON_FINISH_STATUS, finishStatus,
                            FinishConstants.JSON_SUMMARY, summary));
                } catch (Exception e) {
                    log.warn("Failed to process finish", e);
                    return "{\"status\":\"" + FinishConstants.STATUS_FINISHED + "\"}";
                }
            }

            // wait：挂起-恢复，不阻塞。
            // 这里绝对不能 sleep —— 一睡就占住 maxParallel 信号量并撞上 tool.execution-timeout，
            // 而「等超过 60s」恰恰是常规工具语义下无法满足的需求。只写一条挂起记录就返回，
            // SessionRunner 循环判定后 break 退出，到点由 PendingWaitWakeScheduler 唤醒。
            if (CAPABILITY_TYPE_BUILTIN.equals(type) && WaitConstants.TOOL_NAME.equals(tc.name())) {
                try {
                    JsonNode argsNode = JSON.readTree(args);
                    int waitMs = argsNode.has("ms") ? argsNode.get("ms").asInt(WaitConstants.MIN_WAIT_MS) : 0;
                    pendingWaitSpi.createPending(sessionId, runId, tc.id(), waitMs);
                    return JSON.writeValueAsString(Map.of(
                            WaitConstants.JSON_STATUS, WaitConstants.STATUS_WAITING,
                            WaitConstants.JSON_WAIT_MS, waitMs));
                } catch (Exception e) {
                    log.warn("Failed to process wait", e);
                    return JSON_ERROR_WAIT;
                }
            }

            if (CAPABILITY_TYPE_BUILTIN.equals(type)) {
                String result = builtinSpi.executeBuiltinTool(
                        (String) binding.get(ExecBindingKeys.BUILTIN_INTERNAL_NAME), args, sessionId, runId);
                String todowriteName = LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId();
                if (tc.name().equals(todowriteName)) {
                    persistTodosFromResult(sessionId, runId, result);
                }
                return result;
            }
            if (CAPABILITY_TYPE_CLI.equals(type)) {
                CliExecutionBinding cliBinding = new CliExecutionBinding(
                        (String) binding.get(ExecBindingKeys.CLI_COMMAND_TEMPLATE),
                        (String) binding.get(ExecBindingKeys.CLI_WORKING_DIR));
                return cliExecutorService.execute(cliBinding, args);
            }
            return JSON_ERROR_UNSUPPORTED_TYPE + type + "\"}";
        } catch (Exception e) {
            log.warn("Tool execution failed: tool={}", tc.name(), e);
            return JSON_ERROR_EXECUTION + e.getMessage() + "\"}";
        }
    }

    /**
     * 取当前任务的任务级 MCP 凭证。
     *
     * <p>从 Redis 按 sessionId 取，而不是从工具入参或 binding 里取：凭证一旦进入模型可见的
     * 任何一处（入参、binding、prompt）就等于交给模型保管，Bole 侧的「按收藏者隔离」随之失效。
     * 取不到时返回 null，MCP 调用退化为无凭证，服务端会拒绝 —— 这是安全默认值。
     */
    private String resolveTaskMcpCredential(Long sessionId) {
        // sessionId 为 null 或 Redis 里没有凭证时返回 null：McpCallContext 会退化为「不带凭证」，
        // 服务端随即拒绝 —— 安全默认值，且不会让整个 MCP 通道不可用。
        return taskMcpCredentialStore.get(sessionId);
    }

    public String resolveDisplayName(String toolName, List<RuntimeTool> tools) {
        if (tools == null || tools.isEmpty()) return toolName;
        return tools.stream()
                .filter(t -> toolName.equals(t.getLlmToolName()))
                .map(RuntimeTool::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(toolName);
    }

    public String resolveDisplayNameEn(String toolName, List<RuntimeTool> tools) {
        if (tools == null || tools.isEmpty()) return toolName;
        return tools.stream()
                .filter(t -> toolName.equals(t.getLlmToolName()))
                .map(RuntimeTool::getDisplayNameEn)
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(toolName);
    }

    /** delegate 可能触发隔离子 agent（subagent/DAG），按子 Agent 预算放宽外层超时。 */
    public boolean isSubRunTool(String toolName, List<RuntimeTool> tools) {
        if (toolName == null || tools == null || tools.isEmpty()) return false;
        if (!SubAgentConstants.DELEGATE_TOOL.equals(toolName)) return false;
        return tools.stream().anyMatch(t -> toolName.equals(t.getLlmToolName()));
    }

    private void persistTodosFromResult(Long sessionId, Long runId, String resultJson) {
        try {
            TodowriteResultDTO result = JSON.readValue(resultJson, TodowriteResultDTO.class);
            var items = result.getTodos();
            if (items == null || items.isEmpty()) {
                sessionTodoSpi.replaceAll(sessionId, runId, List.of());
                return;
            }
            List<SessionTodoVO> todos = items.stream()
                    .map(item -> {
                        SessionTodoVO vo = new SessionTodoVO(item.getContent(), item.getStatus(), item.getPriority());
                        vo.setSkillId(item.getSkillId());
                        vo.setStepId(item.getStepId());
                        return vo;
                    })
                    .toList();
            sessionTodoSpi.replaceAll(sessionId, runId, todos);
        } catch (Exception e) {
            log.warn("Failed to persist todowrite result for session {}", sessionId, e);
        }
    }
}

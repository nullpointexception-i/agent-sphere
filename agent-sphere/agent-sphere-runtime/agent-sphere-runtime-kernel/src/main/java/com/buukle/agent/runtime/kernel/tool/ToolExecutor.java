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
import com.buukle.agent.runtime.kernel.constants.ProgressConstants;
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
import com.buukle.agent.runtime.kernel.service.ToolResultReadService;
import com.buukle.agent.runtime.kernel.runner.sub.DelegateService;
import com.buukle.agent.runtime.kernel.runner.sub.SubAgentConstants;
import com.buukle.agent.util.json.ToolCallReason;
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
    private static final String JSON_ERROR_TARGET = "{\"error\":\"Target user cannot receive this question\"}";
    private static final String JSON_ERROR_WAIT = "{\"error\":\"Failed to process wait\"}";
    private static final String JSON_ERROR_UNSUPPORTED_TYPE = "{\"error\":\"Unsupported capability type: ";
    private static final String JSON_ERROR_EXECUTION = "{\"error\":\"";

    private final List<CapabilityMcpSpi> mcpSpis;
    private final CapabilityBuiltinSpi builtinSpi;
    private final CliExecutorService cliExecutorService;
    private final SessionTodoSpi sessionTodoSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final ClarificationSpi clarificationSpi;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<
            com.buukle.agent.instance.spi.ClarificationTargetValidator> clarificationTargetValidator;
    private final PendingWaitSpi pendingWaitSpi;
    private final DelegateService delegateService;
    private final ToolResultReadService toolResultReadService;
    /** Source of task-level MCP credentials. */
    private final TaskMcpCredentialStore taskMcpCredentialStore;

    /** Main-loop tool entry point: executes with the root context (the main agent has no parent restriction). */
    public String execute(TurnToolCall tc, Long sessionId, Long runId, List<RuntimeTool> tools) {
        return execute(tc, SubRunExecutionContext.root(sessionId, runId, null), tools);
    }

    /** Executes with a nested context: inner tools of a Skill sub-loop use the sub-context (depth/stack/whitelist). */
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
            String args = ToolCallReason.strip(
                    tc.arguments() != null ? tc.arguments() : RunnerConstants.EMPTY_JSON_ARGS);
            String runMode = ctx.getKernelContext() != null && ctx.getKernelContext().getRun() != null
                    ? ctx.getKernelContext().getRun().getRunMode() : null;
            String denied = ToolAccessGuard.denyReason(tool, args, runMode);
            if (denied != null) {
                return denied;
            }

            // Unified delegate execution entry (pseudo-tool): inline in main or isolated as subagent; depth is uniformly increased by the policy
            if (SubAgentConstants.DELEGATE_TOOL.equals(tc.name())) {
                // Depth stays the same here; only the toolCallId that triggered this call and the current owner are recorded, so nested sub-runs can trace back
                SubRunExecutionContext execCtx = ctx.child(ctx.getDepth(), ctx.getToolRefAncestry(),
                        tc.id(), ctx.getOwnerSubAgentRunId());
                return delegateService.execute(args, execCtx, tools);
            }

            if (CAPABILITY_TYPE_MCP.equals(type) && !mcpSpis.isEmpty()) {
                Map<String, Object> mcpBinding = tool.getExecBinding();
                String serverUrl = mcpBinding == null ? null
                        : (String) mcpBinding.get(ExecBindingKeys.MCP_SERVER_URL);
                String nativeToolName = mcpBinding == null ? null
                        : (String) mcpBinding.get(ExecBindingKeys.MCP_NATIVE_TOOL_NAME);
                String taskMcpCredential = resolveTaskMcpCredential(sessionId);
                Long mcpId = tool.getCapabilityId();
                if (mcpId != null) {
                    return mcpSpis.get(0).executeToolById(mcpId, nativeToolName, args, taskMcpCredential);
                }
                return mcpSpis.get(0).executeTool(serverUrl, nativeToolName, args, taskMcpCredential);
            }
            if (CAPABILITY_TYPE_MCP_HUB.equals(type) && !mcpSpis.isEmpty()) {
                Map<String, Object> mcpBinding = tool.getExecBinding();
                String serverUrl = mcpBinding == null ? null
                        : (String) mcpBinding.get(ExecBindingKeys.MCP_SERVER_URL);
                String nativeToolName = mcpBinding == null ? null
                        : (String) mcpBinding.get(ExecBindingKeys.MCP_NATIVE_TOOL_NAME);
                String taskMcpCredential = resolveTaskMcpCredential(sessionId);
                Long hubId = tool.getCapabilityId();
                if (hubId != null) {
                    return mcpSpis.get(0).executeHubTool(hubId, nativeToolName, args, taskMcpCredential);
                }
                return mcpSpis.get(0).executeTool(serverUrl, nativeToolName, args, taskMcpCredential);
            }
            if (CAPABILITY_TYPE_BUILTIN.equals(type) && ChatClarification.CLARIFICATION_TOOL_NAME.equals(tc.name())) {
                try {
                    JsonNode argsNode = JSON.readTree(args);
                    String title = argsNode.has("title") ? argsNode.get("title").asText() : "";
                    String clarifyType = argsNode.has("type") ? argsNode.get("type").asText() : ChatClarification.CLARIFYING_DEFAULT_TYPE;
                    boolean multiple = argsNode.path(ChatClarification.CLARIFYING_JSON_MULTIPLE).asBoolean(false);
                    String optionsJson = argsNode.has("options") ? argsNode.get("options").toString() : null;
                    String description = argsNode.has("description") ? argsNode.get("description").asText() : null;
                    Long targetUserId = argsNode.has("targetUserId") && !argsNode.get("targetUserId").isNull()
                            ? argsNode.get("targetUserId").asLong() : null;
                    java.time.LocalDateTime expiresAt = null;
                    if (targetUserId != null) {
                        var validator = clarificationTargetValidator.getIfAvailable();
                        if (validator != null && !validator.canTarget(sessionId, targetUserId)) {
                            return JSON_ERROR_TARGET;
                        }
                        long hours = argsNode.path("expiresInHours").asLong(
                                com.buukle.agent.common.constant.ProjectAssistantConstants.QUESTION_EXPIRY_HOURS);
                        hours = Math.min(Math.max(hours, 1), 168);
                        expiresAt = java.time.LocalDateTime.now().plusHours(hours);
                    }
                    String clarificationId = java.util.UUID.randomUUID().toString().substring(0, CLARIFICATION_ID_LENGTH);
                    // Create the DB row first, then emit the PENDING event: Timeline needs lookupClarificationRef to find the record when building the clarification row,
                    // otherwise the row has refClarificationId=null and after a refresh resolveContent cannot get response/options (leaving only "answered").
                    clarificationSpi.createPending(sessionId, runId, runId, title, clarifyType,
                            multiple, optionsJson, description, clarificationId, targetUserId, expiresAt);
                    eventPublisher.publishEvent(new RuntimeEventVO(
                            ClarificationStatus.PENDING,
                            RuntimeEventDataVO.builder()
                                    .runId(runId)
                                    .sessionId(sessionId)
                                    .prompt(title)
                                    .type(clarifyType)
                                    .multiple(multiple)
                                    .argumentsJson(optionsJson)
                                    .clarificationId(clarificationId).build()));
                    return JSON.writeValueAsString(Map.of(
                            ChatClarification.CLARIFYING_JSON_STATUS, ChatClarification.CLARIFYING_STATUS_AWAITING_USER,
                            ChatClarification.CLARIFYING_JSON_CLARIFICATION_ID, clarificationId
                    ));
                } catch (Exception e) {
                    log.warn("Failed to process ask_clarification", e);
                    return JSON_ERROR_CLARIFICATION;
                }
            }

            // finish: normal finalization happens in SessionRunner (reads the input directly and short-circuits the loop).
            // This is only a fallback — if someone executes it directly, return an explicit result instead of "Unknown builtin tool".
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

            // progress is a pure runtime protocol tool: it records the normal tool lifecycle but performs no external side effects.
            if (CAPABILITY_TYPE_BUILTIN.equals(type) && ProgressConstants.TOOL_NAME.equals(tc.name())) {
                try {
                    JsonNode argsNode = JSON.readTree(args);
                    String summary = argsNode.path(ProgressConstants.JSON_SUMMARY).asText("").trim();
                    String nextAction = argsNode.path(ProgressConstants.JSON_NEXT_ACTION).asText("").trim();
                    JsonNode percentNode = argsNode.get(ProgressConstants.JSON_PERCENT);
                    boolean validPercent = percentNode == null
                            || (percentNode.isIntegralNumber() && percentNode.asInt() >= 0 && percentNode.asInt() <= 100);
                    if (summary.isEmpty() || summary.length() > ProgressConstants.MAX_SUMMARY_CHARS
                            || nextAction.length() > ProgressConstants.MAX_NEXT_ACTION_CHARS || !validPercent) {
                        return "{\"error\":\"Invalid progress payload\"}";
                    }
                    return JSON.writeValueAsString(Map.of(
                            "status", ProgressConstants.STATUS_REPORTED,
                            ProgressConstants.JSON_SUMMARY, summary));
                } catch (Exception e) {
                    log.warn("Failed to process progress", e);
                    return "{\"error\":\"Invalid progress payload\"}";
                }
            }

            // wait: suspend-resume, non-blocking.
            // Absolutely must not sleep here — sleeping would hold the maxParallel semaphore and hit tool.execution-timeout,
            // and "waiting longer than 60s" is exactly the requirement that normal tool semantics cannot satisfy. Write only a suspension row and return;
            // SessionRunner decides and breaks out of the loop, and PendingWaitWakeScheduler wakes the run when due.
            if (CAPABILITY_TYPE_BUILTIN.equals(type)
                    && tc.name().equals(LLM_PREFIX_BUILTIN + BuiltinToolEnum.READ_TOOL_RESULT.getId())) {
                return toolResultReadService.read(sessionId, args);
            }

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
                        (String) binding.get(ExecBindingKeys.BUILTIN_INTERNAL_NAME), args, sessionId, runId, tc.id());
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
     * Fetches the current task's task-level MCP credentials.
     *
     * <p>Fetched from Redis by sessionId, not from the tool input or binding: once a credential enters any
     * model-visible place (input, binding, prompt) it is effectively handed to the model, and the collector-based isolation on the Bole side stops working.
     * Returns null when unavailable; the MCP call then degrades to no credentials and the server rejects it — this is the safe default.
     */
    private String resolveTaskMcpCredential(Long sessionId) {
        // Returns null when sessionId is null or Redis has no credential: McpCallContext degrades to "no credentials",
        // and the server rejects it right away — a safe default that does not make the whole MCP channel unusable.
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

    /** delegate may trigger an isolated sub-agent (subagent/DAG); relax the outer timeout according to the sub-agent budget. */
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

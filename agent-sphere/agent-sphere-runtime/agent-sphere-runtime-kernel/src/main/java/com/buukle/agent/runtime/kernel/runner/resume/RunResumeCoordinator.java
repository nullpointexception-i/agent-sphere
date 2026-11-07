package com.buukle.agent.runtime.kernel.runner.resume;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.enums.ToolApprovalState;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.ToolApprovalVO;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.common.constant.ToolApprovalDecision;
import com.buukle.agent.runtime.kernel.loader.HistoryLoader;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.runtime.kernel.port.vo.ToolDeniedResultVO;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.runtime.kernel.port.SubRunExecutionContext;
import com.buukle.agent.runtime.kernel.runner.prompt.PromptTailWriter;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.kernel.tool.ToolAccessGuard;
import com.buukle.agent.common.constant.ToolSecurityLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-entry paths into a run: approval resume, transient-retry resume and recovery resume.
 *
 * <p>Keeps reconstruction / approval semantics out of the loop; each entry returns the
 * updated {@link ResumeState} the loop should continue from.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunResumeCoordinator {

    private final ToolApprovalService toolApprovalService;
    private final ToolApprovalGrantSpi toolApprovalGrantSpi;
    private final RunSpi runSpi;
    private final com.buukle.agent.runtime.kernel.tool.ToolExecutor toolExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final RunPromptBuilder runPromptBuilder;
    private final HistoryLoader historyLoader;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final PromptTailWriter promptTailWriter;

    /** Applies a pending tool-approval resume; returns false when the run cannot be resumed. */
    public boolean applyApprovalResume(Long sessionId, Long currentRunId, RunVO currentRun, KernelContext topCtx,
                                       List<ChatMessageDTO> messages, ResumeState resume) {
        String approvalResumeId = topCtx != null ? topCtx.getApprovalResumeId() : null;
        if (approvalResumeId == null || currentRun == null) {
            return true;
        }
        ToolApprovalVO approval = toolApprovalService.getByApprovalId(approvalResumeId);
        if (approval == null || !ToolApprovalState.APPROVED.equals(approval.getStatus())) {
            currentRun.setStatus(RunStatus.FAILED.name());
            currentRun.setErrorMessage("Tool approval is no longer resumable: " + approvalResumeId);
            runSpi.updateRun(currentRun);
            eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .errorMessage(currentRun.getErrorMessage())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + currentRunId).build()));
            return false;
        }
        appendSystemPromptAndHistory(sessionId, currentRunId, topCtx, messages, resume);

        List<RuntimeTool> resumeTools = topCtx.getTools() != null ? topCtx.getTools() : List.of();
        List<ToolApprovalService.ApprovalCall> approvalCalls = toolApprovalService.calls(approval, resumeTools);
        Set<String> resumeCallIds = new HashSet<>();
        for (ToolApprovalService.ApprovalCall call : approvalCalls) {
            resumeCallIds.add(call.call().id());
        }
        // Remove placeholder tool messages for calls that are still PENDING.
        messages.removeIf(m -> LlmRoles.TOOL.equals(m.getRole())
                && resumeCallIds.contains(m.getToolCallId()));
        Map<String, String> denied = collectDeniedApprovalCalls(sessionId, currentRunId, currentRun,
                approval, approvalCalls);

        for (ToolApprovalService.ApprovalCall call : approvalCalls) {
            executeApprovedCall(sessionId, currentRunId, topCtx, resumeTools, call, denied, messages);
        }
        toolApprovalService.consume(approvalResumeId);
        topCtx.setApprovalResumeId(null);
        promptTailWriter.refreshTodolistSlot(sessionId, messages);
        resume.hasToolCalls = true;
        resume.toolsEverExecuted = true;
        resume.loopCount++;
        return true;
    }

    /** Retry re-entry after a transient LLM failure: rebuild history only, with no resume hint. */
    public void applyRetryResume(Long sessionId, Long currentRunId, RunVO currentRun, KernelContext topCtx,
                                 List<ChatMessageDTO> messages, ResumeState resume) {
        if (topCtx == null || !topCtx.isRetryResume() || currentRun == null) {
            return;
        }
        appendSystemPromptAndHistory(sessionId, currentRunId, topCtx, messages, resume);
        promptTailWriter.refreshTodolistSlot(sessionId, messages);
        resume.hasToolCalls = true;
        // toolsEverExecuted is driven by persisted loopCount and must not be forced here.
        topCtx.setRetryResume(false);
        log.info("Resumed run {} after transient LLM failure with {} history message(s)",
                currentRunId, messages.size());
    }

    /** Recovery re-entry after a server restart: reattach a tail user anchor so the model knows it was interrupted. */
    public void applyRecoveryResume(Long sessionId, Long currentRunId, RunVO currentRun, KernelContext topCtx,
                                    List<ChatMessageDTO> messages, ResumeState resume) {
        if (topCtx == null || !topCtx.isRecoveryResume() || currentRun == null) {
            return;
        }
        toolCallRecordSpi.failRunningByRunId(currentRunId, "interrupted by server restart");
        appendSystemPromptAndHistory(sessionId, currentRunId, topCtx, messages, resume);
        // Replayed history only has run.getUserMessage(); add a synthetic tail user anchor.
        messages.add(new ChatMessageDTO()
                .setRole(LlmRoles.USER)
                .setContent(RunnerConstants.RUN_RECOVERY_RESUME_PROMPT));
        promptTailWriter.refreshTodolistSlot(sessionId, messages);
        resume.hasToolCalls = true;
        resume.toolsEverExecuted = true;
        topCtx.setRecoveryResume(false);
        log.info("Recovered interrupted run {} with {} history message(s)", currentRunId, messages.size());
    }

    private Map<String, String> collectDeniedApprovalCalls(Long sessionId, Long currentRunId, RunVO currentRun,
                                                           ToolApprovalVO approval,
                                                           List<ToolApprovalService.ApprovalCall> approvalCalls) {
        Map<String, ToolApprovalDecision> decisions = toolApprovalService.decisions(approval);
        Map<String, String> denied = new HashMap<>();
        for (ToolApprovalService.ApprovalCall call : approvalCalls) {
            String callId = call.call().id();
            String reason = ToolAccessGuard.denyReason(
                    call.tool(), call.call().arguments(), currentRun.getRunMode());
            if (reason == null && call.pending()
                    && decisions.getOrDefault(callId, ToolApprovalDecision.DENY)
                    == ToolApprovalDecision.DENY) {
                reason = userDeniedJson(call.call().name());
            }
            if (reason == null
                    && ToolSecurityLevel.from(call.tool().getSecurityLevel()).requiresApproval()
                    && toolApprovalGrantSpi.isDenied(sessionId, currentRunId, call.tool().getToolRef())) {
                reason = userDeniedJson(call.call().name());
            }
            if (reason == null && !call.pending()
                    && ToolSecurityLevel.from(call.tool().getSecurityLevel()).requiresApproval()
                    && !toolApprovalGrantSpi.isGranted(sessionId, currentRunId,
                    call.tool().getToolRef(), call.tool().getApprovalScope())) {
                reason = userDeniedJson(call.call().name());
            }
            if (reason != null) {
                denied.put(callId, reason);
            }
        }
        return denied;
    }

    private void executeApprovedCall(Long sessionId, Long currentRunId, KernelContext topCtx,
                                     List<RuntimeTool> resumeTools, ToolApprovalService.ApprovalCall call,
                                     Map<String, String> denied, List<ChatMessageDTO> messages) {
        String callId = call.call().id();
        eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.RUNNING,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(currentRunId)
                        .toolName(call.call().name())
                        .displayNameCn(toolExecutor.resolveDisplayName(call.call().name(), resumeTools))
                        .displayNameEn(toolExecutor.resolveDisplayNameEn(call.call().name(), resumeTools))
                        .argumentsJson(call.call().arguments())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + callId).build()));
        String result;
        if (denied.containsKey(callId)) {
            result = denied.get(callId);
            eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.FAILED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .toolName(call.call().name())
                            .displayNameCn(toolExecutor.resolveDisplayName(call.call().name(), resumeTools))
                            .displayNameEn(toolExecutor.resolveDisplayNameEn(call.call().name(), resumeTools))
                            .artifact(result)
                            .errorMessage("User denied tool execution")
                            .argumentsJson(call.call().arguments())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + callId).build()));
        } else {
            try {
                result = toolExecutor.execute(call.call(),
                        SubRunExecutionContext.root(sessionId, currentRunId, topCtx), resumeTools);
            } catch (Exception e) {
                result = "{\"error\":\"" + e.getMessage() + "\"}";
            }
            eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(currentRunId)
                            .toolName(call.call().name())
                            .displayNameCn(toolExecutor.resolveDisplayName(call.call().name(), resumeTools))
                            .displayNameEn(toolExecutor.resolveDisplayNameEn(call.call().name(), resumeTools))
                            .artifact(result)
                            .compressEnabled(compressEnabled(call.call().name(), resumeTools))
                            .argumentsJson(call.call().arguments())
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + callId).build()));
        }
        String toolMessage = result;
        if (call.call().name().equals(
                InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.TODOWRITE.getId())) {
            toolMessage = RunnerConstants.TODO_WRITE_ACK;
        }
        messages.add(new ChatMessageDTO()
                .setRole(LlmRoles.TOOL)
                .setToolCallId(callId)
                .setContent(toolMessage));
    }

    private void appendSystemPromptAndHistory(Long sessionId, Long currentRunId, KernelContext topCtx,
                                              List<ChatMessageDTO> messages, ResumeState resume) {
        String systemPrompt = runPromptBuilder.buildSystemPrompt(topCtx);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new ChatMessageDTO().setRole(LlmRoles.SYSTEM).setContent(systemPrompt));
        }
        int historyBase = messages.size();
        HistoryLoader.HistoryResult history = historyLoader.loadResult(sessionId, currentRunId, true);
        if (history != null) {
            messages.addAll(history.messages());
        }
        resume.currentRunStart = history == null || history.currentRunStart() < 0
                ? messages.size()
                : historyBase + history.currentRunStart();
    }

    private static String userDeniedJson(String toolName) {
        return ToolDeniedResultVO.of(toolName).toString();
    }

    private static boolean compressEnabled(String toolName, List<RuntimeTool> tools) {
        if (tools == null) return false;
        return tools.stream()
                .filter(t -> toolName.equals(t.getLlmToolName()))
                .findFirst().map(RuntimeTool::isCompressEnabled).orElse(false);
    }
}

package com.buukle.agent.runtime.orchestration.service;


import com.buukle.agent.instance.dtvo.enums.RunType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.dto.ResolveClarificationsDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.ToolApprovalState;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.dtvo.vo.ClarificationAnswerPayloadVO;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionRuntimeStateVO;
import com.buukle.agent.instance.dtvo.vo.ToolApprovalVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer;
import com.buukle.agent.runtime.kernel.service.CompactionService;
import com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.runner.support.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.orchestration.constants.ChatConstant;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ReasoningCapabilityVO;
import com.buukle.agent.runtime.orchestration.orchestrator.RuntimeOrchestrator;
import com.buukle.agent.runtime.orchestration.pipeline.RuntimeValidator;
import com.buukle.agent.runtime.orchestration.pipeline.ValidationResult;
import com.buukle.agent.runtime.orchestration.handler.TimelineRecorder;
import com.buukle.agent.util.TextSanitizer;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRuntimeService {

    private final RuntimeOrchestrator orchestrator;
    private final RunSpi runSpi;
    private final SessionSpi sessionSpi;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentPendingClarificationMapper clarificationMapper;
    private final SessionRunner sessionRunner;
    private final TimelineRecorder timelineRecorder;
    private final ChatAttachmentService chatAttachmentService;
    /** When stopping a run parked in WAITING, cancel its un-woken wait records so the waker cannot resume a run that was already stopped. */
    private final PendingWaitSpi pendingWaitSpi;
    private final ToolApprovalService toolApprovalService;
    private final SessionInputManager inputManager;
    private final RuntimeValidator runtimeValidator;
    private final ReasoningEffortResolver reasoningEffortResolver;
    private final LlmRequestConfigurer llmRequestConfigurer;
    private final CompactionService compactionService;

    public ChatMessageResponseVO chat(Long sessionId, SendMessageDTO dto) {
        log.info("Chat request: sessionId={}, message={}", sessionId, dto.getMessage());
        assertMessageOrAttachment(dto);
        assertSessionOwnership(sessionId);
        sessionSpi.assertSurfaceAllowed(sessionId);

        RunVO active = runSpi.findActiveRun(sessionId);
        if (active != null) {
            String queueId = inputManager.queue(sessionId, dto.getMessage(),
                    dto.getModelRouteId(), false,
                    chatAttachmentService.resolve(dto.getAttachmentKeys()),
                    dto.getReasoningEffort());
            ChatMessageResponseVO queued = new ChatMessageResponseVO();
            queued.setRunId(active.getId());
            queued.setStatus(ChatConstant.RESPONSE_STATUS_QUEUED);
            queued.setQueueId(queueId);
            sessionSpi.touchSession(sessionId);
            return queued;
        }

        RunVO run = createRun(sessionId, dto);
        startRun(run, sessionId, dto, false);

        // Unified Timeline: record the user message row (the body is resolved per run at read time)
        try {
            timelineRecorder.record(sessionId, run.getId(), TimelineKind.USER.getCode(), null,
                TimelineState.COMPLETED.getCode(), null, run.getId(), null, null, null, null);
        } catch (Exception e) {
            log.warn("Timeline user row failed: {}", e.getMessage());
        }

        ChatMessageResponseVO response = new ChatMessageResponseVO();
        response.setRunId(run.getId());
        response.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
        return response;
    }

    /**
     * Create a run (including delivery mode / session ownership checks) without emitting events; the caller uses the returned runId to register the stream, then calls {@link #startRun}.
     */
    public RunVO createRun(Long sessionId, SendMessageDTO dto) {
        assertSessionOwnership(sessionId);
        // Source surface (console / widget) switch: stop the conversation when the instance has disabled that source.
        // The task path is an internal server call without a source; SessionSurfaceHolder.current() returns INTERNAL → allowed.
        sessionSpi.assertSurfaceAllowed(sessionId);
        sessionSpi.touchSession(sessionId);
        SessionVO session = sessionSpi.getSession(sessionId);
        CreateRunDTO createRunDTO = new CreateRunDTO();
        createRunDTO.setSessionId(sessionId);
        createRunDTO.setThreadId(dto.getThreadId());
        createRunDTO.setRunMode(RunMode.from(
                dto.getRunMode() != null ? dto.getRunMode() : session.getRunMode()).name());
        createRunDTO.setUserMessage(TextSanitizer.sanitize(dto.getMessage()));
        createRunDTO.setType(RunType.AUTO);
        createRunDTO.setNoClarification(dto.getNoClarification());
        createRunDTO.setAttachments(resolveRunAttachments(dto));
        createRunDTO.setReasoningEffort(dto.getReasoningEffort());
        RunVO run = runSpi.createRun(createRunDTO);
        if (dto.getDelivery() != null) {
            run.setDelivery(dto.getDelivery());
        }
        log.info("Run created: runId={}, type={}", run.getId(), run.getType());
        return run;
    }

    /** Attachment references persisted with the run (only fileKey+contentType; bytes stay in agent_file_store) for Timeline display. */
    private List<RunAttachment> resolveRunAttachments(SendMessageDTO dto) {
        if (dto.getAttachmentKeys() == null || dto.getAttachmentKeys().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return chatAttachmentService.resolve(dto.getAttachmentKeys()).stream()
                .map(a -> new RunAttachment(a.fileKey(), a.contentType()))
                .toList();
    }

    /** Message and attachments cannot both be empty (image-only sends allow empty text). */
    private static void assertMessageOrAttachment(SendMessageDTO dto) {
        boolean blankMessage = dto.getMessage() == null || dto.getMessage().isBlank();
        boolean hasAttachment = dto.getAttachmentKeys() != null && !dto.getAttachmentKeys().isEmpty();
        if (blankMessage && !hasAttachment) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Message and attachments cannot both be empty");
        }
    }

    /**
     * Publish RUN_STARTED (PENDING) and start the runner asynchronously.
     */
    public void startRun(RunVO run, Long sessionId, SendMessageDTO dto, boolean isClarificationResume) {
        eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.PENDING,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(run.getId())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId()).build()));

        orchestrator.asyncHandleUserMessage(run, sessionId, dto.getMessage(), dto.getModelRouteId(),
                isClarificationResume, chatAttachmentService.resolve(dto.getAttachmentKeys()));
        log.info("Async execution started for runId={}", run.getId());
    }

    /**
     * Wait due: fill in that wait tool call's result as "waited" and resume the original run.
     *
     * <p>Does not reuse {@link #resumeFromClarification}, because the resume semantics differ: clarification resume
     * carries the user's answer text and rebuilds the clarification row; here there is no user input, it just completes
     * the final result for a tool call that already returned {@code {"status":"waiting"}}, then pushes the loop forward again.
     *
     * <p>The caller must have won the {@code PendingWaitSpi#claimForResume} claim — the in-memory timer and
     * the cron compensation can hit the same record at once; without the claim the same run would be resumed twice.
     */
    /** Wait wake outcome, used by the wake path to decide "done / mark terminal / requeue for retry". */
    public enum ResumeOutcome { RESUMED, SKIPPED, ERROR }

    /**
     * Wait due: fill in that wait tool call's result as "waited" and resume the original run.
     *
     * <p><b>No longer swallows exceptions</b>: returns {@link ResumeOutcome} so the caller can decide to restore PENDING and retry on failure
     * (see {@code requeueForRetry}) — otherwise "resume failure after claim" leaves the run stuck in WAITING forever.
     *
     * <p>The caller must have won the {@code PendingWaitSpi#claimForResume} claim.
     */
    public ResumeOutcome resumeFromWait(PendingWaitVO wait) {
        // Only called by background wake/reconcile paths: clear tenant possibly inherited by the thread,
        // otherwise DataPermission filters by someone else's created_by and the run reads as null.
        TenantUtil.stop();
        AuthContext.clear();
        Long sessionId = wait.getSessionId();
        Long runId = wait.getRunId();
        boolean submitted = false;
        try {
            // Cancel may land just before the wake: check the cancel flag first, then CAS WAITING -> RUNNING.
            if (sessionRunner.isRunCancelled(runId) || sessionRunner.isSessionCancelled(sessionId)) {
                log.info("Wait resume skipped: runId={} cancelled", runId);
                return ResumeOutcome.SKIPPED;
            }
            RunVO run = runSpi.claimForResume(runId);
            if (run == null) {
                // Already cancelled/finalized, or woken by another path: nothing to resume; let the caller mark it terminal
                log.info("Wait resume skipped: runId={} not WAITING", runId);
                return ResumeOutcome.SKIPPED;
            }
            // Re-check after the CAS: when cancel races with claim, cancel wins (the claim already set the run to RUNNING and must be reverted)
            if (sessionRunner.isRunCancelled(runId) || sessionRunner.isSessionCancelled(sessionId)) {
                runSpi.cancelIfActive(runId);
                log.info("Wait resume aborted after claim: runId={} cancelled", runId);
                return ResumeOutcome.SKIPPED;
            }

            // The resume thread (timer/cron) has no tenant context: fill in the username for audit/ownership.
            // Deliberately does not set TenantUtil — DataPermissionInterceptor filters by tenant as created_by,
            // and setting it could instead filter out system table (model/instance) rows.
            String createdBy = run.getCreatedBy();
            boolean ctx = createdBy != null && !createdBy.isBlank();
            boolean llmRetry = WaitConstants.KIND_LLM_RETRY.equals(wait.getKind());
            if (ctx) AuthContext.setUsername(createdBy);
            try {
                sessionSpi.touchSession(sessionId);
                if (llmRetry) {
                    // Retry of a transient upstream failure: no synthetic user message, no wait tool result, just re-enter the loop
                    orchestrator.asyncResumeForRetry(run, sessionId);
                } else {
                    orchestrator.asyncHandleUserMessage(run, sessionId,
                            WaitConstants.WAIT_RESUME_PREFIX + wait.getWaitMs() + "ms elapsed.",
                            run.getModelRouteId(), true, List.of());
                }
                submitted = true;
            } finally {
                if (ctx) AuthContext.clear();
            }

            if (!llmRetry) {
                // Only after the resume is submitted, complete that wait tool call's result (avoiding "tool recorded as succeeded but the run did not resume").
                // This is a display-only event and **must never be able to fail the resume** — if it threw and pushed the state back to
                // WAITING while the async loop is already running, it would create a second loop out of nowhere (see rollbackToWaiting).
                recordWaitToolSucceeded(wait, sessionId, runId);
                long waitedMs = wait.getWaitMs() == null ? 0L : wait.getWaitMs();
                timelineRecorder.markWaitResumed(sessionId, runId, waitedMs);
                log.info("Wait resumed: waitId={} runId={} sessionId={} waitedMs={}",
                        wait.getId(), runId, sessionId, wait.getWaitMs());
            } else {
                // Retry submitted: change the "retrying (k/N)" row back to "running", so the whole retry window does not stay on stale text.
                // The event backfills the same row's seq/title so the frontend updates in place, without waiting for the next suspension or terminal state.
                TimelineRecorder.RunStatusRow row = timelineRecorder.markRetryResumed(sessionId, runId);
                if (row != null) {
                    eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.RUNNING,
                            RuntimeEventDataVO.builder()
                                    .sessionId(sessionId)
                                    .runId(runId)
                                    .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + runId)
                                    .seq(row.seq())
                                    .kind(TimelineKind.RUN_STATUS.getCode())
                                    .statusTitle(row.title())
                                    .status(RunStatus.RUNNING.value()).build()));
                }
                log.info("LLM retry resumed: waitId={} runId={} sessionId={} attempt={} hint={}",
                        wait.getId(), runId, sessionId, wait.getAttempts(), wait.getReason());
            }
            return ResumeOutcome.RESUMED;
        } catch (Exception e) {
            log.warn("Wait resume failed: waitId={} runId={} submitted={}", wait.getId(), runId, submitted, e);
            if (submitted) {
                // Async continuation already submitted: the loop is running. Rolling back the state now would put the wait record back to PENDING, let the cron
                // claim it and <b>submit again</b>, with two loops writing the same run concurrently. Only log ERROR and let the caller retry/mark terminal.
                log.warn("Wait resume failed after async submit; not rolling back run {} to avoid duplicate execution", runId);
            } else {
                rollbackToWaiting(runId);
            }
            return ResumeOutcome.ERROR;
        }
    }

    /** Record the success result of that wait tool call; failures only warn and do not affect the resume outcome. */
    private void recordWaitToolSucceeded(PendingWaitVO wait, Long sessionId, Long runId) {
        if (wait.getCallId() == null || wait.getCallId().isBlank()) {
            return;
        }
        try {
            long nowMs = System.currentTimeMillis();
            String artifact = "{\"status\":\"waiting\",\"elapsed_ms\":" + wait.getWaitMs()
                    + ",\"resumed_at\":\"" + java.time.LocalDateTime.now() + "\""
                    + ",\"resumed_at_ms\":" + nowMs + "}";
            eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(runId)
                            .toolName(WaitConstants.TOOL_NAME)
                            .displayNameCn(WaitConstants.DISPLAY_NAME_CN)
                            .displayNameEn(WaitConstants.DISPLAY_NAME)
                            .artifact(artifact)
                            .publishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + wait.getCallId()).build()));
        } catch (Exception e) {
            log.warn("Failed to record wait tool success: waitId={} runId={}", wait.getId(), runId, e);
        }
    }

    /**
     * Put the run back to WAITING when resume fails.
     *
     * <p>{@code resumeFromWait} sets the run to RUNNING before submitting the async continuation; if it then throws,
     * the run stays RUNNING with no loop running. The next retry is judged SKIPPED because the state is not WAITING,
     * and {@code failStuckWait} returns immediately on the same mismatch — so the run neither resumes nor fails
     * and stays "running" forever. The failure path must therefore roll the state back itself.
     */
    private void rollbackToWaiting(Long runId) {
        try {
            // Conditional rollback: only a run still RUNNING goes back to WAITING, so cancelled/terminal states are not overwritten
            runSpi.markWaiting(runId);
        } catch (Exception e) {
            log.warn("Wait resume rollback failed: runId={}", runId, e);
        }
    }

    /**
     * Mark a stuck WAITING run FAILED and emit the terminal event (called when retries are exhausted / reconciliation rescue fails),
     * so the frontend does not stay on "running" forever.
     */
    public void failStuckWait(Long sessionId, Long runId, String reason) {
        RunVO run = runSpi.getRun(runId);
        if (run == null || !RunStatus.WAITING.name().equals(run.getStatus())) {
            return;
        }
        run.setStatus(RunStatus.FAILED.name());
        runSpi.updateRun(run);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(runId)
                        .assistantReply("Failed to resume wait: " + reason)
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + runId).build()));
    }

    /**
     * Handle one wake outcome: <b>never leave a record at RESUMED after a claim</b>.
     * SKIPPED → mark terminal CANCELLED; ERROR → restore PENDING and retry, and once exhausted mark FAILED and finalize the stuck run.
     */
    public void onResumeOutcome(PendingWaitVO wait, ResumeOutcome outcome) {
        if (outcome == ResumeOutcome.RESUMED) {
            return;
        }
        if (outcome == ResumeOutcome.SKIPPED) {
            pendingWaitSpi.markTerminal(wait.getId(), WaitConstants.CANCELLED_STATUS, "run not waiting");
            return;
        }
        int attempts = pendingWaitSpi.requeueForRetry(wait.getId(), "resume error");
        if (attempts < 0 || attempts >= WaitConstants.MAX_RESUME_ATTEMPTS) {
            pendingWaitSpi.markTerminal(wait.getId(), WaitConstants.FAILED_STATUS, "resume failed after retries");
            failStuckWait(wait.getSessionId(), wait.getRunId(),
                    "wait resume failed after " + Math.max(attempts, 0) + " attempt(s)");
        } else {
            log.info("Wait requeued for retry: waitId={} attempts={}", wait.getId(), attempts);
        }
    }

    public ChatMessageResponseVO resumeFromClarification(Long sessionId, Long runId, String response, String clarificationId) {
        assertSessionOwnership(sessionId);

        LambdaQueryWrapper<AgentPendingClarification> query = new LambdaQueryWrapper<AgentPendingClarification>()
                .eq(AgentPendingClarification::getRunId, runId)
                .isNull(AgentPendingClarification::getUserResponse)
                .orderByDesc(AgentPendingClarification::getCreatedAt);
        if (clarificationId != null && !clarificationId.isBlank()) {
            query.eq(AgentPendingClarification::getClarificationId, clarificationId);
        }
        AgentPendingClarification pending = clarificationMapper.selectOne(
                query.last(ChatClarification.CLARIFYING_SQL_LIMIT));
        if (pending == null) {
            // Idempotency: the clarification may already be answered (duplicate submit) or the run may have moved on. Do not throw,
            // otherwise a swallowed duplicate submit leaves the frontend without a terminal state and stuck on "running".
            AgentPendingClarification latest = clarificationMapper.selectOne(
                    new LambdaQueryWrapper<AgentPendingClarification>()
                            .eq(AgentPendingClarification::getRunId, runId)
                            .orderByDesc(AgentPendingClarification::getCreatedAt)
                            .last(ChatClarification.CLARIFYING_SQL_LIMIT));
            if (latest != null && latest.getUserResponse() != null) {
                ChatMessageResponseVO result = new ChatMessageResponseVO();
                result.setRunId(runId);
                result.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
                return result;
            }
            throw new BizException(CommonErrorCode.PARAM_INVALID, ChatClarification.CLARIFYING_ERROR_NOT_FOUND);
        }
        pending.setUserResponse(response);
        clarificationMapper.updateById(pending);

        String resumeText = ChatClarification.CLARIFICATION_RESUME_PREFIX + response;

        // Notify frontend which clarification was responded and with what text
        eventPublisher.publishEvent(new RuntimeEventVO(
                ClarificationStatus.RESPONDED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(runId)
                        .clarificationId(pending.getClarificationId())
                        .response(response).build()));

        sessionSpi.touchSession(sessionId);

        // Try to resume the original run; fall back to forking a new run.
        RunVO originalRun = runSpi.getRun(runId);
        if (originalRun != null && RunStatus.AWAITING_USER.name().equals(originalRun.getStatus())) {
            originalRun.setStatus(RunStatus.RUNNING.name());
            runSpi.updateRun(originalRun);
            log.info("Clarification resume: reusing runId={} for session={}", runId, sessionId);
            orchestrator.asyncHandleUserMessage(originalRun, sessionId, resumeText, originalRun.getModelRouteId(), true);

            ChatMessageResponseVO result = new ChatMessageResponseVO();
            result.setRunId(runId);
            result.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
            return result;
        }

        // Fork — create a new run
        SessionVO session = sessionSpi.getSession(sessionId);
        CreateRunDTO createRunDTO = new CreateRunDTO();
        createRunDTO.setSessionId(sessionId);
        createRunDTO.setUserMessage(ChatClarification.CLARIFICATION_RESUME_PREFIX + response);
        createRunDTO.setType(RunType.AUTO);
        createRunDTO.setRunMode(RunMode.from(session.getRunMode()).name());
        RunVO run = runSpi.createRun(createRunDTO);
        log.info("Clarification fork: new runId={} for session={} from previous runId={}", run.getId(), sessionId, runId);

        eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.PENDING,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(run.getId())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId()).build()));

        orchestrator.asyncHandleUserMessage(run, sessionId, resumeText, null, true);

        ChatMessageResponseVO result = new ChatMessageResponseVO();
        result.setRunId(run.getId());
        result.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
        return result;
    }

    public List<ClarificationVO> pendingClarifications(Long sessionId) {
        assertSessionOwnership(sessionId);
        List<AgentPendingClarification> rows = clarificationMapper.selectList(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getSessionId, sessionId)
                        .isNull(AgentPendingClarification::getUserResponse)
                        .orderByAsc(AgentPendingClarification::getCreatedAt));
        RunVO active = runSpi.findActiveRun(sessionId);
        if (active != null) {
            rows = rows.stream()
                    .filter(row -> active.getId().equals(row.getRunId()))
                    .toList();
        }
        List<ClarificationVO> out = new ArrayList<>();
        for (AgentPendingClarification row : rows) {
            ClarificationVO vo = new ClarificationVO();
            vo.setClarificationId(row.getClarificationId());
            vo.setRunId(row.getRunId());
            vo.setSessionId(row.getSessionId());
            vo.setMessageId(row.getMessageId());
            vo.setTitle(row.getTitle());
            vo.setType(row.getType());
            vo.setMultiple(Boolean.TRUE.equals(row.getMultiple()));
            vo.setOptions(row.getOptions());
            vo.setDescription(row.getDescription());
            vo.setStatus(ChatConstant.CLARIFICATION_STATUS_PENDING);
            out.add(vo);
        }
        return out;
    }

    public ChatMessageResponseVO resolveClarifications(Long sessionId, Long runId,
                                                       ResolveClarificationsDTO dto) {
        assertSessionOwnership(sessionId);
        List<AgentPendingClarification> pending = clarificationMapper.selectList(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getRunId, runId)
                        .isNull(AgentPendingClarification::getUserResponse)
                        .orderByAsc(AgentPendingClarification::getCreatedAt));
        if (pending.isEmpty()) {
            return processingResponse(runId);
        }
        Map<String, ResolveClarificationsDTO.Answer> answers = new LinkedHashMap<>();
        if (dto != null && dto.getAnswers() != null) {
            for (ResolveClarificationsDTO.Answer answer : dto.getAnswers()) {
                if (answer != null && answer.getClarificationId() != null) {
                    answers.put(answer.getClarificationId(), answer);
                }
            }
        }
        for (AgentPendingClarification row : pending) {
            ResolveClarificationsDTO.Answer answer = answers.get(row.getClarificationId());
            if (answer == null || (isEmpty(answer.getSelected())
                    && (answer.getText() == null || answer.getText().isBlank()))) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "Every pending clarification must be answered");
            }
        }

        StringBuilder resumeText = new StringBuilder(ChatClarification.CLARIFICATION_RESUME_PREFIX);
        for (AgentPendingClarification row : pending) {
            ResolveClarificationsDTO.Answer answer = answers.get(row.getClarificationId());
            String responseJson = answerJson(answer);
            row.setUserResponse(responseJson);
            clarificationMapper.updateById(row);
            eventPublisher.publishEvent(new RuntimeEventVO(
                    ClarificationStatus.RESPONDED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(runId)
                            .clarificationId(row.getClarificationId())
                            .multiple(Boolean.TRUE.equals(row.getMultiple()))
                            .response(answerText(answer)).build()));
            resumeText.append("\nQ: ").append(row.getTitle() == null ? "" : row.getTitle())
                    .append("\nA: ").append(answerText(answer));
        }
        sessionSpi.touchSession(sessionId);
        return resumeRunWithText(sessionId, runId, resumeText.toString());
    }

    private ChatMessageResponseVO resumeRunWithText(Long sessionId, Long runId, String resumeText) {
        RunVO originalRun = runSpi.getRun(runId);
        if (originalRun != null && RunStatus.AWAITING_USER.name().equals(originalRun.getStatus())) {
            originalRun.setStatus(RunStatus.RUNNING.name());
            runSpi.updateRun(originalRun);
            orchestrator.asyncHandleUserMessage(originalRun, sessionId, resumeText, originalRun.getModelRouteId(), true);
            return processingResponse(runId);
        }

        SessionVO session = sessionSpi.getSession(sessionId);
        CreateRunDTO createRunDTO = new CreateRunDTO();
        createRunDTO.setSessionId(sessionId);
        createRunDTO.setUserMessage(resumeText);
        createRunDTO.setType(RunType.AUTO);
        createRunDTO.setRunMode(RunMode.from(session.getRunMode()).name());
        RunVO run = runSpi.createRun(createRunDTO);
        eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.PENDING,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(run.getId())
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId()).build()));
        orchestrator.asyncHandleUserMessage(run, sessionId, resumeText, null, true);
        return processingResponse(run.getId());
    }

    private static ChatMessageResponseVO processingResponse(Long runId) {
        ChatMessageResponseVO result = new ChatMessageResponseVO();
        result.setRunId(runId);
        result.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
        return result;
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private static String answerJson(ResolveClarificationsDTO.Answer answer) {
        ClarificationAnswerPayloadVO payload = new ClarificationAnswerPayloadVO();
        payload.setSelected(answer.getSelected() == null ? List.of() : answer.getSelected());
        payload.setText(answer.getText() == null ? "" : answer.getText());
        return JsonUtils.toJson(payload);
    }

    private static String answerText(ResolveClarificationsDTO.Answer answer) {
        String selected = answer.getSelected() == null ? "" : String.join(", ", answer.getSelected());
        String text = answer.getText() == null ? "" : answer.getText().trim();
        if (selected.isBlank()) return text;
        if (text.isBlank()) return selected;
        return selected + "; " + text;
    }

    public List<SessionInputManager.InputMessage> pendingQueue(Long sessionId) {
        assertSessionOwnership(sessionId);
        return inputManager.listPending(sessionId);
    }

    public SessionInputManager.WithdrawResult withdrawQueue(Long sessionId, String queueId) {
        assertSessionOwnership(sessionId);
        return inputManager.withdraw(sessionId, queueId);
    }

    public com.buukle.agent.instance.dtvo.vo.ToolApprovalVO pendingToolApproval(Long sessionId) {
        assertSessionOwnership(sessionId);
        return toolApprovalService.getPending(sessionId);
    }

    /**
     * Session run state: the single authoritative source for send-lock and pending decisions.
     *
     * <p>All fields come from source tables; {@code agent_timeline} is not read.
     */
    public SessionRuntimeStateVO getState(Long sessionId) {
        assertSessionOwnership(sessionId);
        RunVO active = runSpi.findActiveRun(sessionId);
        List<ClarificationVO> clarifications = pendingClarifications(sessionId);
        ToolApprovalVO approval = pendingToolApproval(sessionId);
        int queueSize = pendingQueue(sessionId).size();
        SessionVO session = sessionSpi.getSession(sessionId);

        // Only pending items belonging to the active run can be answered by the user; with no active run, historical leftovers must not keep the input locked
        int pendingClarificationCount = active == null ? 0 : clarifications.size();
        int pendingApprovalCount = active != null && approval != null
                && active.getId().equals(approval.getRunId()) ? 1 : 0;

        SessionRuntimeStateVO vo = new SessionRuntimeStateVO();
        vo.setSessionId(sessionId);
        vo.setRunId(active == null ? null : active.getId());
        vo.setRunStatus(active == null ? null : active.getStatus());
        vo.setLoopCount(active == null ? null : active.getLoopCount());
        vo.setLoopLimit(active == null ? null : active.getLoopLimit());
        vo.setRunMode(session == null ? null : session.getRunMode());
        vo.setPendingClarifications(pendingClarificationCount);
        vo.setPendingToolApprovals(pendingApprovalCount);
        vo.setPendingQueue(queueSize);
        // LLM retry countdown: RunVO is already enriched by fillRetryState (when WAITING + LLM_RETRY)
        if (active != null) {
            vo.setWaitKind(active.getWaitKind());
            vo.setRetryAttempt(active.getRetryAttempt());
            vo.setRetryMax(active.getRetryMax());
            vo.setNextRetryAt(active.getNextRetryAt());
            if (active.getNextRetryAt() != null) {
                vo.setRetryRemainingMs(Math.max(0L,
                        active.getNextRetryAt() - System.currentTimeMillis()));
            }
        }

        SessionActionPolicy.Decision decision = SessionActionPolicy.decide(
                vo.getRunStatus(), vo.getPendingClarifications(), vo.getPendingToolApprovals());
        vo.setCanSend(decision.canSend());
        vo.setBlockReasons(decision.blockReasons());
        vo.setServerTime(java.time.LocalDateTime.now());
        return vo;
    }

    /**
     * Session reasoning-effort capability: dynamic default level + feasibility per level.
     *
     * <p>Infeasible levels are still returned (the frontend shows and warns about them); only "supported" decides whether the whole block is displayed.
     * When the route/key is not ready, do not error; return supported=false so the chat page does not fail because of a capability call.
     */
    public ReasoningCapabilityVO reasoningCapability(Long sessionId, Long modelRouteId) {
        assertSessionOwnership(sessionId);
        RunVO active = runSpi.findActiveRun(sessionId);
        ReasoningCapabilityVO vo = new ReasoningCapabilityVO();
        vo.setRunning(active != null);
        vo.setRunId(active == null ? null : active.getId());

        // While running, use only the route pinned on the run; when idle, use the route the user currently selected (an unsent override).
        Long routeId = active != null && active.getModelRouteId() != null
                ? active.getModelRouteId() : modelRouteId;
        ValidationResult validated;
        try {
            validated = runtimeValidator.validate(sessionId, routeId);
        } catch (BizException e) {
            log.warn("Reasoning capability unavailable: sessionId={}, reason={}", sessionId, e.getMessage());
            vo.setSupported(false);
            vo.setLevels(List.of());
            return vo;
        }

        Integer instanceMaxOutput = llmRequestConfigurer.resolveMaxOutputTokens(
                validated.getAgentInstance().getConfig());
        // Same source as SessionRunner: instance config.max_tokens first, then the route's max_output_tokens.
        // The actual per-turn max_tokens is further shrunk by remaining context; this is the nominal cap.
        int nominal = compactionService.resolveNominalChatMaxOutput(
                instanceMaxOutput, validated.getModelRoute().getMaxOutputTokens());
        ReasoningEffortResolver.Resolved resolved = reasoningEffortResolver.resolve(
                validated.getModelRoute(), validated.getModelRoute().getConfig(),
                nominal, active == null ? null : active.getReasoningEffort());

        vo.setSupported(resolved.supported());
        vo.setMode(resolved.mode());
        vo.setEffectiveMaxOutputTokens(resolved.effectiveMaxOutputTokens());
        vo.setAnswerReserveTokens(resolved.answerReserveTokens());
        vo.setDefaultLevel(resolved.defaultLevel());
        vo.setSelectedLevel(resolved.selectedLevel());
        vo.setEffectiveLevel(resolved.effectiveLevel());
        List<ReasoningCapabilityVO.Level> levels = new ArrayList<>();
        for (ReasoningEffortResolver.LevelView view : resolved.levels()) {
            ReasoningCapabilityVO.Level level = new ReasoningCapabilityVO.Level();
            level.setValue(view.value());
            level.setMinOutputTokens(view.minOutputTokens());
            level.setFeasible(view.feasible());
            levels.add(level);
        }
        vo.setLevels(levels);
        return vo;
    }

    public void resolveToolApproval(Long sessionId, Long runId, String approvalId, String decisionsJson) {
        assertSessionOwnership(sessionId);
        ToolApprovalVO approval = toolApprovalService.getByApprovalId(approvalId);
        if (approval == null
                || !runId.equals(approval.getRunId())
                || !sessionId.equals(approval.getSessionId())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Tool approval not found");
        }
        if (ToolApprovalState.PENDING.equals(approval.getStatus())) {
            if (!toolApprovalService.resolve(sessionId, runId, approvalId, decisionsJson)) {
                throw new BizException(CommonErrorCode.PARAM_INVALID,
                        "Tool approval is not pending or decisions are invalid");
            }
        } else if (!ToolApprovalState.APPROVED.equals(approval.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "Tool approval is already closed");
        }

        RunVO run = runSpi.getRun(runId);
        if (run != null && RunStatus.AWAITING_USER.name().equals(run.getStatus())) {
            run.setStatus(RunStatus.RUNNING.name());
            runSpi.updateRun(run);
            orchestrator.asyncResumeToolApproval(run, sessionId, approvalId);
        }
    }

    private static boolean isApprovalResponse(String response) {
        if (response == null) return false;
        String v = response.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("allow") || v.equals("approve") || v.equals("approved")
                || v.equals("yes") || v.equals("允许") || v.equals("确认");
    }

    /**
     * Session-level stop: does not depend on a runId. A running run is terminated by the SessionRunner loop via CANCELLED_SESSIONS;
     * the boundaries for parked states (AWAITING_USER) and for no active run are handled here.
     */
    public void stopSession(Long sessionId) {
        assertSessionOwnership(sessionId);
        sessionRunner.cancelSession(sessionId);
        RunVO active = runSpi.findActiveRun(sessionId);
        if (active == null) {
            // No active run: clear the flag so a newly started run is not killed by mistake;
            // but the session may still have unanswered clarifications (the run is terminal/cleaned up), so emit dismissed explicitly,
            // otherwise the frontend stays frozen on pending clarification / "running".
            sessionRunner.clearSessionCancelled(sessionId);
            dismissPendingClarifications(sessionId);
        } else if (RunStatus.AWAITING_USER.name().equals(active.getStatus())
                || RunStatus.WAITING.name().equals(active.getStatus())) {
            // Parked state (waiting for a person / for time): the loop has exited and the flag would not take effect → reuse stopRun's parked-state
            // finalization (CANCELLED + cancel waits + emit terminal event), then clear the flag.
            stopRun(sessionId, active.getId());
            sessionRunner.clearSessionCancelled(sessionId);
        } else {
            // RUNNING/PENDING → first use stopRun to write the run-level cancel flag and conditionally mark terminal,
            // covering the window where "the loop exits and suspends just before the cancel"; then clear the session flag.
            stopRun(sessionId, active.getId());
            sessionRunner.clearSessionCancelled(sessionId);
        }
        // RUNNING/PENDING → the loop checks CANCELLED_SESSIONS to terminate; the finalization point consumes the flag and publishes the terminal state
    }

    public void stopRun(Long sessionId, Long runId) {
        assertSessionOwnership(sessionId);
        RunVO run = runSpi.getRun(runId);
        if (run == null) return;

        if (RunStatus.AWAITING_USER.name().equals(run.getStatus())
                || RunStatus.WAITING.name().equals(run.getStatus())) {
            // Parked state (waiting for a person / for time): the loop has exited, so nothing consumes the cancel flag → mark terminal directly.
            // Still write the run-level cancel flag: if the loop happens to be wrapping up, the wake/resume path must see the cancel;
            // otherwise a wait inserted after the cancel could still be woken (production run 2999 at 14:58:39: the first cancel did not take effect).
            sessionRunner.cancelRun(runId);
            // Conditional update: do not finalize again when another path already did.
            if (!runSpi.cancelIfActive(runId)) {
                pendingWaitSpi.cancelPendingByRunId(runId);
                toolApprovalService.cancelByRunId(runId);
                return;
            }

            List<AgentPendingClarification> pendingList = clarificationMapper.selectList(
                    new LambdaQueryWrapper<AgentPendingClarification>()
                            .eq(AgentPendingClarification::getRunId, runId)
                            .isNull(AgentPendingClarification::getUserResponse));
            for (AgentPendingClarification pending : pendingList) {
                pending.setUserResponse(ChatClarification.CLARIFICATION_RESPONSE_DISMISSED);
                clarificationMapper.updateById(pending);
                eventPublisher.publishEvent(new RuntimeEventVO(
                        ClarificationStatus.DISMISSED,
                        RuntimeEventDataVO.builder()
                                .sessionId(sessionId)
                                .runId(runId)
                                .clarificationId(pending.getClarificationId()).build()));
            }

            int cancelledWaits = pendingWaitSpi.cancelPendingByRunId(runId);
            if (cancelledWaits > 0) {
                log.info("Stop run {}: cancelled {} pending wait record(s)", runId, cancelledWaits);
            }
            toolApprovalService.cancelByRunId(runId);
            publishCancelled(sessionId, runId);
        } else {
            // RUNNING/PENDING: write the run-level cancel flag first (a live loop exits at its next checkpoint),
            // then conditionally set CANCELLED, covering the window where "the loop has exited but the run is still WAITING/RUNNING".
            sessionRunner.cancelRun(runId);
            boolean cancelled = runSpi.cancelIfActive(runId);
            pendingWaitSpi.cancelPendingByRunId(runId);
            toolApprovalService.cancelByRunId(runId);
            if (cancelled) {
                publishCancelled(sessionId, runId);
            }
        }
    }

    private void publishCancelled(Long sessionId, Long runId) {
        eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.CANCELLED,
                RuntimeEventDataVO.builder()
                        .sessionId(sessionId)
                        .runId(runId)
                        .assistantReply(RunnerConstants.CANCEL_MSG)
                        .publishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + runId).build()));
    }

    /**
     * With no active run, emit dismissed events as a fallback for this session's unanswered clarifications:
     * the run may be terminal/cleaned up, but the clarification row is still PENDING, which locks the frontend on "running".
     */
    private void dismissPendingClarifications(Long sessionId) {
        List<AgentPendingClarification> pendingList = clarificationMapper.selectList(
                new LambdaQueryWrapper<AgentPendingClarification>()
                        .eq(AgentPendingClarification::getSessionId, sessionId)
                        .isNull(AgentPendingClarification::getUserResponse));
        for (AgentPendingClarification pending : pendingList) {
            pending.setUserResponse(ChatClarification.CLARIFICATION_RESPONSE_DISMISSED);
            clarificationMapper.updateById(pending);
            eventPublisher.publishEvent(new RuntimeEventVO(
                    ClarificationStatus.DISMISSED,
                    RuntimeEventDataVO.builder()
                            .sessionId(sessionId)
                            .runId(pending.getRunId())
                            .clarificationId(pending.getClarificationId()).build()));
        }
    }

    private void assertSessionOwnership(Long sessionId) {
        if (AuthContext.isSuperAdmin()) return;
        SessionVO session = sessionSpi.getSession(sessionId);
        if (session == null || !AuthContext.getUsername().equals(session.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}

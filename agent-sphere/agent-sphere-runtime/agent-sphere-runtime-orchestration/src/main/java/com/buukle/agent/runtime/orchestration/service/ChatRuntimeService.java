package com.buukle.agent.runtime.orchestration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.dtvo.dto.CreateRunDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.constants.RuntimeEventTypeConstant;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.ToolCallStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.orchestration.constants.ChatConstant;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.orchestrator.RuntimeOrchestrator;
import com.buukle.agent.runtime.orchestration.handler.TimelineRecorder;
import com.buukle.agent.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

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
    /** 停止 WAITING 停车态的 run 时，取消其名下未唤醒的挂起记录，避免 waker 恢复已停止的 run。 */
    private final PendingWaitSpi pendingWaitSpi;

    public ChatMessageResponseVO chat(Long sessionId, SendMessageDTO dto) {
        log.info("Chat request: sessionId={}, message={}", sessionId, dto.getMessage());
        assertMessageOrAttachment(dto);

        RunVO run = createRun(sessionId, dto);
        startRun(run, sessionId, dto, false);

        // 统一 Timeline：记录用户消息行（正文在读取时按 run 解析）
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
     * 创建 run（含交付方式/会话归属校验），不发事件；由调用方拿到 runId 完成流注册后再 {@link #startRun}。
     */
    public RunVO createRun(Long sessionId, SendMessageDTO dto) {
        assertSessionOwnership(sessionId);
        // 来源端（主站 / widget）开关：实例已关掉该来源时禁止继续对话。
        // 任务路径是服务端内部调用、不携带来源，SessionSurfaceHolder.current() 返回 INTERNAL → 放行。
        sessionSpi.assertSurfaceAllowed(sessionId);
        sessionSpi.touchSession(sessionId);
        CreateRunDTO createRunDTO = new CreateRunDTO();
        createRunDTO.setSessionId(sessionId);
        createRunDTO.setUserMessage(TextSanitizer.sanitize(dto.getMessage()));
        createRunDTO.setType(RunEnum.TYPE_AUTO);
        createRunDTO.setNoClarification(dto.getNoClarification());
        createRunDTO.setAttachments(resolveRunAttachments(dto));
        RunVO run = runSpi.createRun(createRunDTO);
        if (dto.getDelivery() != null) {
            run.setDelivery(dto.getDelivery());
        }
        log.info("Run created: runId={}, type={}", run.getId(), run.getType());
        return run;
    }

    /** 附件引用随 run 落库（仅 fileKey+contentType，字节留 agent_file_store），Timeline 回显用。 */
    private List<RunAttachment> resolveRunAttachments(SendMessageDTO dto) {
        if (dto.getAttachmentKeys() == null || dto.getAttachmentKeys().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return chatAttachmentService.resolve(dto.getAttachmentKeys()).stream()
                .map(a -> new RunAttachment(a.fileKey(), a.contentType()))
                .toList();
    }

    /** 消息与附件不可同时为空（纯图片发送允许空文本）。 */
    private static void assertMessageOrAttachment(SendMessageDTO dto) {
        boolean blankMessage = dto.getMessage() == null || dto.getMessage().isBlank();
        boolean hasAttachment = dto.getAttachmentKeys() != null && !dto.getAttachmentKeys().isEmpty();
        if (blankMessage && !hasAttachment) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "消息内容和附件不能同时为空");
        }
    }

    /**
     * 发布 RUN_STARTED（PENDING）并异步启动 runner。
     */
    public void startRun(RunVO run, Long sessionId, SendMessageDTO dto, boolean isClarificationResume) {
        eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.PENDING,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(run.getId())
                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())));

        orchestrator.asyncHandleUserMessage(run, sessionId, dto.getMessage(), dto.getModelRouteId(),
                isClarificationResume, chatAttachmentService.resolve(dto.getAttachmentKeys()));
        log.info("Async execution started for runId={}", run.getId());
    }

    /**
     * wait 等待到期：把那次 wait 的工具结果补成「已等待」，并恢复原 run。
     *
     * <p>不复用 {@link #resumeFromClarification}，因为两者恢复语义不同：澄清恢复要
     * 带用户应答文本并重建澄清行；这里没有用户输入，只是把一个已经返回
     * {@code {"status":"waiting"}} 的工具调用补上最终结果，然后重新推进循环。
     *
     * <p>调用方必须已用 {@code PendingWaitSpi#claimForResume} 抢占成功 —— 内存定时器与
     * cron 补偿会同时命中同一条记录，没有抢占就会把同一个 run 恢复两次。
     */
    /** wait 唤醒结果，供唤醒路径决定"完成 / 终态化 / 回置重试"。 */
    public enum ResumeOutcome { RESUMED, SKIPPED, ERROR }

    /**
     * wait 等待到期：把那次 wait 的工具结果补成「已等待」，并恢复原 run。
     *
     * <p><b>不再吞异常</b>：返回 {@link ResumeOutcome}，由调用方决定失败时回置 PENDING 重试
     * （见 {@code requeueForRetry}）—— 否则「claim 后恢复失败」会永久卡 WAITING。
     *
     * <p>调用方必须已用 {@code PendingWaitSpi#claimForResume} 抢占成功。
     */
    public ResumeOutcome resumeFromWait(PendingWaitVO wait) {
        Long sessionId = wait.getSessionId();
        Long runId = wait.getRunId();
        boolean submitted = false;
        try {
            RunVO run = runSpi.getRun(runId);
            if (run == null) {
                log.warn("Wait resume skipped: run {} no longer exists", runId);
                return ResumeOutcome.SKIPPED;
            }
            if (!RunStatus.WAITING.name().equals(run.getStatus())) {
                // 已被取消/收口，或已被另一条路径唤醒：无从恢复，交给调用方终态化
                log.info("Wait resume skipped: runId={} status={} (expected {})", runId, run.getStatus(), RunStatus.WAITING);
                return ResumeOutcome.SKIPPED;
            }
            run.setStatus(RunStatus.RUNNING.name());
            runSpi.updateRun(run);

            // 恢复线程（定时器/cron）无租户上下文：补 username 供审计/归属。
            // 刻意不设 TenantUtil —— DataPermissionInterceptor 用租户当 created_by 过滤，
            // 设了反而可能把系统表（模型/实例）行滤掉。
            String createdBy = run.getCreatedBy();
            boolean ctx = createdBy != null && !createdBy.isBlank();
            if (ctx) AuthContext.setUsername(createdBy);
            try {
                sessionSpi.touchSession(sessionId);
                orchestrator.asyncHandleUserMessage(run, sessionId,
                        WaitConstants.WAIT_RESUME_PREFIX + wait.getWaitMs() + "ms elapsed.", null, true, List.of());
                submitted = true;
            } finally {
                if (ctx) AuthContext.clear();
            }

            // 恢复已提交后才补 wait 那条 tool call 的结果（避免"工具已记成功但 run 没恢复"）。
            // 这只是展示层事件，**绝不能让它有能力让恢复失败** —— 一旦它抛异常而把状态退回
            // WAITING，异步循环其实已经在跑，等于凭空造出第二个循环（见 rollbackToWaiting）。
            recordWaitToolSucceeded(wait, sessionId, runId);
            log.info("Wait resumed: waitId={} runId={} sessionId={} waitedMs={}",
                    wait.getId(), runId, sessionId, wait.getWaitMs());
            return ResumeOutcome.RESUMED;
        } catch (Exception e) {
            log.warn("Wait resume failed: waitId={} runId={} submitted={}", wait.getId(), runId, submitted, e);
            if (submitted) {
                // 异步续跑已提交：循环正在跑。此时回退状态会让 wait 记录重回 PENDING、被 cron 再次
                // claim 并<b>再提交一次</b>，两个循环并发写同一个 run。只能记 ERROR 让调用方重试/终态化。
                log.warn("Wait resume failed after async submit; not rolling back run {} to avoid duplicate execution", runId);
            } else {
                rollbackToWaiting(runId);
            }
            return ResumeOutcome.ERROR;
        }
    }

    /** 补记 wait 那次工具调用的成功结果；失败只告警，不影响恢复结果。 */
    private void recordWaitToolSucceeded(PendingWaitVO wait, Long sessionId, Long runId) {
        if (wait.getCallId() == null || wait.getCallId().isBlank()) {
            return;
        }
        try {
            String artifact = "{\"status\":\"waiting\",\"elapsed_ms\":" + wait.getWaitMs()
                    + ",\"resumed_at\":\"" + java.time.LocalDateTime.now() + "\"}";
            eventPublisher.publishEvent(new RuntimeEventVO(ToolCallStatus.SUCCEEDED,
                    new RuntimeEventDataVO()
                            .setSessionId(sessionId)
                            .setRunId(runId)
                            .setToolName(WaitConstants.TOOL_NAME)
                            .setDisplayNameCn(WaitConstants.DISPLAY_NAME_CN)
                            .setDisplayNameEn(WaitConstants.DISPLAY_NAME)
                            .setArtifact(artifact)
                            .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_TOOL + wait.getCallId())));
        } catch (Exception e) {
            log.warn("Failed to record wait tool success: waitId={} runId={}", wait.getId(), runId, e);
        }
    }

    /**
     * 恢复失败时把 run 改回 WAITING。
     *
     * <p>{@code resumeFromWait} 先把 run 置 RUNNING 再提交异步续跑；一旦后面抛异常，
     * run 就停在 RUNNING 却没有任何 loop 在跑。下一次重试会因状态不是 WAITING 被判 SKIPPED，
     * {@code failStuckWait} 也因状态不匹配直接返回 —— 结果是 run 既没恢复也没失败，
     * 永久停在「执行中」。所以失败路径必须自己把状态退回去。
     */
    private void rollbackToWaiting(Long runId) {
        try {
            RunVO run = runSpi.getRun(runId);
            if (run == null || !RunStatus.RUNNING.name().equals(run.getStatus())) {
                return;
            }
            run.setStatus(RunStatus.WAITING.name());
            runSpi.updateRun(run);
        } catch (Exception e) {
            log.warn("Wait resume rollback failed: runId={}", runId, e);
        }
    }

    /**
     * 把一条卡住的 WAITING run 置 FAILED 并发终态事件（重试超限 / 对账抢救失败时调用），
     * 避免前端永久停在「执行中」。
     */
    public void failStuckWait(Long sessionId, Long runId, String reason) {
        RunVO run = runSpi.getRun(runId);
        if (run == null || !RunStatus.WAITING.name().equals(run.getStatus())) {
            return;
        }
        run.setStatus(RunStatus.FAILED.name());
        runSpi.updateRun(run);
        eventPublisher.publishEvent(new RuntimeEventVO(RunStatus.FAILED,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setAssistantReply("等待恢复失败：" + reason)
                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + runId)));
    }

    /**
     * 处理一次唤醒结果：<b>claim 之后绝不让记录停在 RESUMED</b>。
     * SKIPPED → 终态化 CANCELLED；ERROR → 回置 PENDING 重试，超限则置 FAILED 并把卡住的 run 置终态。
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
            // 幂等：澄清可能已被应答（重复提交）或 run 已推进。不再抛错，
            // 否则前端重复提交被吞后拿不到终态，会永久卡在「执行中」。
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

        // Notify frontend which clarification was responded and with what text
        eventPublisher.publishEvent(new RuntimeEventVO(
                ClarificationStatus.RESPONDED,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(runId)
                        .setClarificationId(pending.getClarificationId())
                        .setResponse(response)));

        sessionSpi.touchSession(sessionId);

        // Try to resume the original run; fall back to forking a new run.
        RunVO originalRun = runSpi.getRun(runId);
        if (originalRun != null && RunStatus.AWAITING_USER.name().equals(originalRun.getStatus())) {
            originalRun.setStatus(RunStatus.RUNNING.name());
            runSpi.updateRun(originalRun);
            log.info("Clarification resume: reusing runId={} for session={}", runId, sessionId);
            orchestrator.asyncHandleUserMessage(originalRun, sessionId,
                    ChatClarification.CLARIFICATION_RESUME_PREFIX + response, null, true);

            ChatMessageResponseVO result = new ChatMessageResponseVO();
            result.setRunId(runId);
            result.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
            return result;
        }

        // Fork — create a new run
        CreateRunDTO createRunDTO = new CreateRunDTO();
        createRunDTO.setSessionId(sessionId);
        createRunDTO.setUserMessage(ChatClarification.CLARIFICATION_RESUME_PREFIX + response);
        createRunDTO.setType(RunEnum.TYPE_AUTO);
        RunVO run = runSpi.createRun(createRunDTO);
        log.info("Clarification fork: new runId={} for session={} from previous runId={}", run.getId(), sessionId, runId);

        eventPublisher.publishEvent(new RuntimeEventVO(
                RunStatus.PENDING,
                new RuntimeEventDataVO()
                        .setSessionId(sessionId)
                        .setRunId(run.getId())
                        .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + run.getId())));

        orchestrator.asyncHandleUserMessage(run, sessionId, ChatClarification.CLARIFICATION_RESUME_PREFIX + response, null, true);

        ChatMessageResponseVO result = new ChatMessageResponseVO();
        result.setRunId(run.getId());
        result.setStatus(ChatConstant.RESPONSE_STATUS_PROCESSING);
        return result;
    }

    /**
     * session 级停止：不依赖 runId。运行中的 run 由 SessionRunner 循环按 CANCELLED_SESSIONS
     * 直接终止；停车态（AWAITING_USER）与无活动 run 的边界在此处理。
     */
    public void stopSession(Long sessionId) {
        assertSessionOwnership(sessionId);
        sessionRunner.cancelSession(sessionId);

        RunVO active = runSpi.findActiveRun(sessionId);
        if (active == null) {
            // 无活动 run：清除标志，避免误杀之后新发起的 run；
            // 但该 session 仍可能残留未应答澄清（run 已终态/被清扫），显式下发 dismissed，
            // 否则前端会一直定格在待澄清/「执行中」。
            sessionRunner.clearSessionCancelled(sessionId);
            dismissPendingClarifications(sessionId);
        } else if (RunStatus.AWAITING_USER.name().equals(active.getStatus())
                || RunStatus.WAITING.name().equals(active.getStatus())) {
            // 停车态（等人 / 等时间）：loop 已退出，标志不会生效 → 复用 stopRun 的停车态
            // 收口（置 CANCELLED + 清挂起 + 发终态）后清除标志。
            stopRun(sessionId, active.getId());
            sessionRunner.clearSessionCancelled(sessionId);
        } else {
            // RUNNING/PENDING → loop 检查 CANCELLED_SESSIONS 终止；同时写 run 级取消键兜底，
            // 避免“session 键被收口清空早于被中断的子 Agent 回收”造成子 Agent 空窗续跑。
            sessionRunner.cancelRun(active.getId());
        }
        // RUNNING/PENDING → loop 检查 CANCELLED_SESSIONS 终止，收口处消费标志并发布终态
    }

    public void stopRun(Long sessionId, Long runId) {
        assertSessionOwnership(sessionId);
        RunVO run = runSpi.getRun(runId);
        if (run == null) return;

        if (RunStatus.AWAITING_USER.name().equals(run.getStatus())
                || RunStatus.WAITING.name().equals(run.getStatus())) {
            // 停车态（等人 / 等时间）：loop 已退出，取消标志无消费者 → 直接置终态。
            // 1) 置 CANCELLED；
            // 2) dismiss 未应答澄清（等人态）；取消未唤醒的挂起记录（等时间态）—— 两者都清，幂等；
            // 3) 发 run_cancelled 终态事件，前端据此解锁并显示“已取消”。
            run.setStatus(RunStatus.CANCELLED.name());
            runSpi.updateRun(run);

            List<AgentPendingClarification> pendingList = clarificationMapper.selectList(
                    new LambdaQueryWrapper<AgentPendingClarification>()
                            .eq(AgentPendingClarification::getRunId, runId)
                            .isNull(AgentPendingClarification::getUserResponse));
            for (AgentPendingClarification pending : pendingList) {
                pending.setUserResponse(ChatClarification.CLARIFICATION_RESPONSE_DISMISSED);
                clarificationMapper.updateById(pending);
                eventPublisher.publishEvent(new RuntimeEventVO(
                        ClarificationStatus.DISMISSED,
                        new RuntimeEventDataVO()
                                .setSessionId(sessionId)
                                .setRunId(runId)
                                .setClarificationId(pending.getClarificationId())));
            }

            int cancelledWaits = pendingWaitSpi.cancelPendingByRunId(runId);
            if (cancelledWaits > 0) {
                log.info("Stop run {}: cancelled {} pending wait record(s)", runId, cancelledWaits);
            }

            eventPublisher.publishEvent(new RuntimeEventVO(
                    RunStatus.CANCELLED,
                    new RuntimeEventDataVO()
                            .setSessionId(sessionId)
                            .setRunId(runId)
                            .setAssistantReply(RunnerConstants.CANCEL_MSG)
                            .setPublishId(RuntimeEventTypeConstant.PUBLISH_ID_RUN + runId)));
        } else {
            sessionRunner.cancelRun(runId);
        }
    }

    /**
     * 无活动 run 时兜底下发该 session 未应答澄清的 dismissed 事件：
     * run 可能已终态/被清扫，但澄清行仍停留在 PENDING，会让前端锁在「执行中」。
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
                    new RuntimeEventDataVO()
                            .setSessionId(sessionId)
                            .setRunId(pending.getRunId())
                            .setClarificationId(pending.getClarificationId())));
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

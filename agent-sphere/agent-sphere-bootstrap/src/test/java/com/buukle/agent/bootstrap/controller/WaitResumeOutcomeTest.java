package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.vo.PendingWaitVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.orchestration.handler.TimelineRecorder;
import com.buukle.agent.runtime.orchestration.orchestrator.RuntimeOrchestrator;
import com.buukle.agent.runtime.orchestration.service.ChatAttachmentService;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.runtime.orchestration.wait.WaitingRunReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for "never stay in RESUMED after claim".
 *
 * <p>Waking is "CAS the wait record to RESUMED, then resume the run". If the resume throws or silently no-ops,
 * {@code listDue}/{@code claimForResume} only accept PENDING and never find the record again, so the run is stuck forever in
 * WAITING (the frontend shows "running" and stop does nothing). Three fallbacks: reset to PENDING and retry on ERROR, set a terminal state past the limit,
 * and rescue via timeout reconciliation.
 */
@ExtendWith(MockitoExtension.class)
class WaitResumeOutcomeTest {

    @Mock
    RuntimeOrchestrator orchestrator;
    @Mock
    RunSpi runSpi;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    AgentPendingClarificationMapper clarificationMapper;
    @Mock
    SessionRunner sessionRunner;
    @Mock
    TimelineRecorder timelineRecorder;
    @Mock
    ChatAttachmentService chatAttachmentService;
    @Mock
    PendingWaitSpi pendingWaitSpi;
    @Mock
    RedissonClient redissonClient;

    ChatRuntimeService service;

    @BeforeEach
    void setUp() {
        service = new ChatRuntimeService(orchestrator, runSpi, sessionSpi, eventPublisher,
                clarificationMapper, sessionRunner, timelineRecorder, chatAttachmentService, pendingWaitSpi, mock(ToolApprovalService.class), mock(SessionInputManager.class),
                mock(com.buukle.agent.runtime.orchestration.pipeline.RuntimeValidator.class),
                mock(com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver.class),
                mock(com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer.class),
                mock(com.buukle.agent.runtime.kernel.service.CompactionService.class));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantUtil.stop();
    }

    private static PendingWaitVO pendingWait() {
        return new PendingWaitVO(11L, 1L, 7L, "call-1", 7000,
                LocalDateTime.now(), WaitConstants.PENDING_STATUS, 0);
    }

    private static RunVO runWithStatus(String status) {
        RunVO run = new RunVO();
        run.setId(7L);
        run.setSessionId(1L);
        run.setStatus(status);
        run.setCreatedBy("alice");
        return run;
    }

    @Test
    void resumeFromWait_returnsErrorWhenResumeThrows_soCallerCanRequeue() {
        when(runSpi.claimForResume(7L)).thenReturn(runWithStatus(RunStatus.RUNNING.name()));
        doThrow(new IllegalStateException("boom")).when(sessionSpi).touchSession(1L);

        assertEquals(ChatRuntimeService.ResumeOutcome.ERROR, service.resumeFromWait(pendingWait()));
        verify(pendingWaitSpi, never()).markTerminal(any(), anyString(), anyString());
    }

    @Test
    void resumeFromWait_clearsInheritedTenantBeforeReadingRun() {
        // The reconcile task also calls resumeFromWait directly; whichever background path enters, there must be no tenant,
        // otherwise data permissions read the run as null (→ misjudged as SKIPPED).
        TenantUtil.start("leaked-user");
        AtomicReference<String> tenantSeenByRead = new AtomicReference<>("not-called");
        when(runSpi.claimForResume(7L)).thenAnswer(inv -> {
            tenantSeenByRead.set(TenantUtil.get());
            return null;
        });

        service.resumeFromWait(pendingWait());

        assertNull(tenantSeenByRead.get(), "恢复入口必须先清掉继承来的租户");
    }

    @Test
    void resumeFromWait_skippedWhenRunAlreadyAdvanced() {
        when(runSpi.claimForResume(7L)).thenReturn(null);

        assertEquals(ChatRuntimeService.ResumeOutcome.SKIPPED, service.resumeFromWait(pendingWait()));
    }

    @Test
    void onResumeOutcome_errorRequeuesForRetryInsteadOfLeavingRowResumed() {
        when(pendingWaitSpi.requeueForRetry(11L, "resume error")).thenReturn(1);

        service.onResumeOutcome(pendingWait(), ChatRuntimeService.ResumeOutcome.ERROR);

        verify(pendingWaitSpi).requeueForRetry(11L, "resume error");
        verify(pendingWaitSpi, never()).markTerminal(any(), anyString(), anyString());
    }

    @Test
    void onResumeOutcome_skippedMarksWaitCancelledAndLeavesRunAlone() {
        service.onResumeOutcome(pendingWait(), ChatRuntimeService.ResumeOutcome.SKIPPED);

        verify(pendingWaitSpi).markTerminal(11L, WaitConstants.CANCELLED_STATUS, "run not waiting");
        verify(pendingWaitSpi, never()).requeueForRetry(any(), anyString());
    }

    @Test
    void onResumeOutcome_errorBeyondLimitFailsStuckRunAndEmitsTerminalEvent() {
        when(pendingWaitSpi.requeueForRetry(11L, "resume error"))
                .thenReturn(WaitConstants.MAX_RESUME_ATTEMPTS);
        when(runSpi.getRun(7L)).thenReturn(runWithStatus(RunStatus.WAITING.name()));

        service.onResumeOutcome(pendingWait(), ChatRuntimeService.ResumeOutcome.ERROR);

        verify(pendingWaitSpi).markTerminal(11L, WaitConstants.FAILED_STATUS, "resume failed after retries");
        ArgumentCaptor<RuntimeEventVO> cap = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher).publishEvent(cap.capture());
        assertEquals(RunStatus.FAILED, cap.getValue().getEventType(),
                "重试耗尽必须发终态事件，否则前端永久停在「执行中」");
    }

    /** @Value does not apply in unit tests without @Spring, so the threshold must be set explicitly. */
    private WaitingRunReconciler reconciler() {
        WaitingRunReconciler reconciler = new WaitingRunReconciler(runSpi, pendingWaitSpi, service, redissonClient);
        ReflectionTestUtils.setField(reconciler, "staleMinutes", 15L);
        return reconciler;
    }

    private void holdLock() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
    }

    @Test
    void reconcile_rescuesRunWithoutPendingWaitAndSkipsThoseStillPending() throws Exception {
        holdLock();

        RunVO withPending = new RunVO();
        withPending.setId(7L);
        withPending.setSessionId(1L);
        withPending.setStatus(RunStatus.WAITING.name());
        RunVO stuck = new RunVO();
        stuck.setId(8L);
        stuck.setSessionId(1L);
        stuck.setStatus(RunStatus.WAITING.name());

        when(runSpi.findStaleByStatus(eq(RunStatus.WAITING.name()), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(withPending, stuck));
        when(pendingWaitSpi.listPendingByRunId(7L)).thenReturn(List.of(pendingWait()));
        when(pendingWaitSpi.listPendingByRunId(8L)).thenReturn(List.of());
        when(runSpi.claimForResume(8L)).thenReturn(runWithStatus(RunStatus.RUNNING.name()));
        when(runSpi.getRun(8L)).thenReturn(stuck);
        // The rescue goes through resumeFromWait: set RUNNING then continue asynchronously; here the continuation throws → ERROR → failStuckWait
        doThrow(new IllegalStateException("boom")).when(sessionSpi).touchSession(1L);

        reconciler().reconcile();

        // run 7 still has a PENDING record → normal path; reconciliation must never preempt the resume
        verify(runSpi, never()).updateRun(withPending);
        // run 8 rescue fails → set FAILED and publish the terminal state
        verify(runSpi, atLeastOnce()).updateRun(stuck);
        assertEquals(RunStatus.FAILED.name(), stuck.getStatus());
        ArgumentCaptor<RuntimeEventVO> cap = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher).publishEvent(cap.capture());
        assertEquals(RunStatus.FAILED, cap.getValue().getEventType(),
                "抢救失败必须发终态事件，否则前端永久停在「执行中」");
    }

    @Test
    void reconcile_doesNothingWhenAnotherReplicaHoldsTheLock() throws Exception {
        RLock busy = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(busy);
        when(busy.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        reconciler().reconcile();

        // k8s replicas=2: without the lock the whole round is skipped, since two replicas rescuing at once would resume the same run twice
        verify(runSpi, never()).findStaleByStatus(anyString(), any(LocalDateTime.class), anyInt());
    }

    @Test
    void resumeFromWait_afterAsyncSubmit_ignoresToolRecordFailureAndKeepsRunning() {
        // Regression guard (two invariants):
        // 1) Recording the tool success afterwards is only a display event; an exception must not downgrade a successful resume to ERROR;
        // 2) Never put the run back to WAITING under any circumstance — if it is, the wait record is reset to PENDING,
        //    claimed again by cron and submitted for another async execution, with two loops writing the same run concurrently.
        when(runSpi.claimForResume(7L)).thenReturn(runWithStatus(RunStatus.RUNNING.name()));
        doThrow(new IllegalStateException("timeline blew up"))
                .when(eventPublisher).publishEvent(any(RuntimeEventVO.class));

        assertEquals(ChatRuntimeService.ResumeOutcome.RESUMED, service.resumeFromWait(pendingWait()),
                "补记事件失败不应让恢复失败");

        verify(runSpi).claimForResume(7L);
        verify(runSpi, never()).markWaiting(7L);
    }

    @Test
    void resumeFromWait_beforeSubmit_rollsBackToWaitingSoRetryCanWork() {
        // Inverse guard: a failure before submission must roll back, otherwise the run sits in RUNNING with no loop running
        when(runSpi.claimForResume(7L)).thenReturn(runWithStatus(RunStatus.RUNNING.name()));
        doThrow(new IllegalStateException("session touch failed"))
                .when(sessionSpi).touchSession(1L);

        assertEquals(ChatRuntimeService.ResumeOutcome.ERROR, service.resumeFromWait(pendingWait()));

        verify(runSpi).markWaiting(7L);
    }
}
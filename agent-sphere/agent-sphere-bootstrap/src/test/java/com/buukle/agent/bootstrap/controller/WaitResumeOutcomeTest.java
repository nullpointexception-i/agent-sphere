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
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
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
 * 「claim 之后绝不能停在 RESUMED」的回归护栏。
 *
 * <p>唤醒是「先 CAS 把挂起记录改成 RESUMED、再恢复 run」。恢复一旦抛异常或静默 no-op，
 * {@code listDue}/{@code claimForResume} 只认 PENDING 就再也捞不到这条记录，run 永久卡在
 * WAITING（前端显示"执行中"、停止按钮无效）。三条兜底：ERROR 回置重试、超限置终态、
 * 超时对账抢救。
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
                clarificationMapper, sessionRunner, timelineRecorder, chatAttachmentService, pendingWaitSpi);
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
        when(runSpi.getRun(7L)).thenReturn(runWithStatus(RunStatus.WAITING.name()));
        doThrow(new IllegalStateException("boom")).when(sessionSpi).touchSession(1L);

        assertEquals(ChatRuntimeService.ResumeOutcome.ERROR, service.resumeFromWait(pendingWait()));
        verify(pendingWaitSpi, never()).markTerminal(any(), anyString(), anyString());
    }

    @Test
    void resumeFromWait_clearsInheritedTenantBeforeReadingRun() {
        // 对账任务也会直接调 resumeFromWait；无论从哪条后台路径进来都必须无租户，
        // 否则 getRun 会被 DataPermission 追加 created_by 过滤成 null（→ 误判 SKIPPED）。
        TenantUtil.start("leaked-user");
        AtomicReference<String> tenantSeenByRead = new AtomicReference<>("not-called");
        when(runSpi.getRun(7L)).thenAnswer(inv -> {
            tenantSeenByRead.set(TenantUtil.get());
            return runWithStatus(RunStatus.CANCELLED.name());
        });

        service.resumeFromWait(pendingWait());

        assertNull(tenantSeenByRead.get(), "恢复入口必须先清掉继承来的租户");
    }

    @Test
    void resumeFromWait_skippedWhenRunAlreadyAdvanced() {
        when(runSpi.getRun(7L)).thenReturn(runWithStatus(RunStatus.CANCELLED.name()));

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

    /** 未加 @Spring 的单元测试里 @Value 不生效，必须显式设阈值。 */
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
        when(runSpi.getRun(8L)).thenReturn(stuck);
        // 抢救走 resumeFromWait：先置 RUNNING 再异步续跑，这里让续跑抛异常 → ERROR → failStuckWait
        doThrow(new IllegalStateException("boom")).when(sessionSpi).touchSession(1L);

        reconciler().reconcile();

        // run 7 名下仍有 PENDING 记录 → 属正常路径，绝不能被对账抢先恢复
        verify(runSpi, never()).updateRun(withPending);
        // run 8 抢救失败 → 置 FAILED 并发终态
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

        // k8s replicas=2：抢不到锁就整轮跳过，两个副本同时抢救会重复恢复同一个 run
        verify(runSpi, never()).findStaleByStatus(anyString(), any(LocalDateTime.class), anyInt());
    }

    @Test
    void resumeFromWait_afterAsyncSubmit_ignoresToolRecordFailureAndKeepsRunning() {
        // 回归护栏（两个不变量）：
        // 1) 补记 tool 成功只是展示事件，抛异常不得把已成功的恢复降级成 ERROR；
        // 2) 任何情况下都不能把 run 退回 WAITING —— 一旦退回，wait 记录会回置 PENDING，
        //    被 cron 再次 claim 并再提交一次异步执行，两个循环并发写同一个 run。
        when(runSpi.getRun(7L)).thenReturn(runWithStatus(RunStatus.WAITING.name()));
        doThrow(new IllegalStateException("timeline blew up"))
                .when(eventPublisher).publishEvent(any(RuntimeEventVO.class));

        assertEquals(ChatRuntimeService.ResumeOutcome.RESUMED, service.resumeFromWait(pendingWait()),
                "补记事件失败不应让恢复失败");

        ArgumentCaptor<RunVO> cap = ArgumentCaptor.forClass(RunVO.class);
        verify(runSpi, atLeastOnce()).updateRun(cap.capture());
        assertEquals(RunStatus.RUNNING.name(), cap.getAllValues()
                        .get(cap.getAllValues().size() - 1).getStatus(),
                "异步续跑已提交后 run 必须停在 RUNNING，不能回退到 WAITING");
    }

    @Test
    void resumeFromWait_beforeSubmit_rollsBackToWaitingSoRetryCanWork() {
        // 反向护栏：提交前失败必须回退，否则 run 卡在 RUNNING 却没有任何循环在跑
        when(runSpi.getRun(7L)).thenReturn(runWithStatus(RunStatus.WAITING.name()));
        doThrow(new IllegalStateException("session touch failed"))
                .when(sessionSpi).touchSession(1L);

        assertEquals(ChatRuntimeService.ResumeOutcome.ERROR, service.resumeFromWait(pendingWait()));

        ArgumentCaptor<RunVO> cap = ArgumentCaptor.forClass(RunVO.class);
        verify(runSpi, atLeastOnce()).updateRun(cap.capture());
        assertEquals(RunStatus.WAITING.name(), cap.getAllValues()
                        .get(cap.getAllValues().size() - 1).getStatus(),
                "提交前失败必须退回 WAITING，重试才能再次匹配");
    }
}
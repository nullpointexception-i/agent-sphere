package com.buukle.agent.runtime.kernel;

import com.buukle.agent.common.event.InterruptedRunRecoveryEvent;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.spi.AgentCompactRecordSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finalization guard for runs stuck in RUNNING: OrphanRunSweeper only matches Redis state=active + no owner,
 * while a run that "exited the loop without writing a terminal state" is set to state=IDLE + no owner — it is never swept.
 * This task falls back to the DB updated_at, but only when "no owner" holds, to avoid killing a live run in a long call.
 */
@ExtendWith(MockitoExtension.class)
class StaleRunningRunReconcilerTest {

    private static final long SESSION_ID = 953L;
    private static final long RUN_ID = 2263L;

    @Mock
    RedissonClient redissonClient;
    @Mock
    RunSpi runSpi;
    @Mock
    AgentCompactRecordSpi compactRecordSpi;
    @Mock
    SessionRunCoordinator coordinator;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    RLock lock;
    @Mock
    RBucket<String> ownerBucket;
    @Mock
    RBucket<String> stateBucket;

    private StaleRunningRunReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock("scheduler:stale-running-reconcile")).thenReturn(lock);
        lenient().when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(redissonClient.<String>getBucket(DistributedRuntimeConstants.sessionOwnerKey(SESSION_ID)))
                .thenReturn(ownerBucket);
        lenient().when(redissonClient.<String>getBucket(DistributedRuntimeConstants.sessionStateKey(SESSION_ID)))
                .thenReturn(stateBucket);
        reconciler = new StaleRunningRunReconciler(redissonClient, runSpi, compactRecordSpi,
                coordinator, eventPublisher, 30, 2);
    }

    private RunVO run(String status) {
        RunVO run = new RunVO();
        run.setId(RUN_ID);
        run.setSessionId(SESSION_ID);
        run.setStatus(status);
        return run;
    }

    private void staleCandidates(RunVO candidate) {
        lenient().when(runSpi.findStaleByStatus(eq(RunStatus.RUNNING.name()), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(candidate));
    }

    @Test
    void staleRunningRunWithoutOwner_isFailedAndSessionReset() throws Exception {
        RunVO run = run(RunStatus.RUNNING.name());
        staleCandidates(run);
        when(ownerBucket.isExists()).thenReturn(false);
        when(runSpi.getRunMeta(RUN_ID)).thenReturn(run);
        lenient().when(stateBucket.get()).thenReturn(SessionRunCoordinator.State.RUNNING.name());

        reconciler.reconcile();

        assertEquals(RunStatus.FAILED.name(), run.getStatus());
        assertEquals(RunnerConstants.RUN_STALE_MSG, run.getErrorMessage());
        verify(runSpi).updateRun(run);
        verify(eventPublisher).publishEvent(any(RuntimeEventVO.class));
        verify(stateBucket).delete();
        verify(coordinator).wake(SESSION_ID);
    }

    @Test
    void idleStateOnlyClosesRun_andDoesNotWake() throws Exception {
        RunVO run = run(RunStatus.RUNNING.name());
        staleCandidates(run);
        when(ownerBucket.isExists()).thenReturn(false);
        when(runSpi.getRunMeta(RUN_ID)).thenReturn(run);
        lenient().when(stateBucket.get()).thenReturn(SessionRunCoordinator.State.IDLE.name());

        reconciler.reconcile();

        verify(runSpi).updateRun(run);
        verify(stateBucket, never()).delete();
        verify(coordinator, never()).wake(SESSION_ID);
    }

    @Test
    void liveOwnerIsNeverKilled() throws Exception {
        RunVO run = run(RunStatus.RUNNING.name());
        staleCandidates(run);
        when(ownerBucket.isExists()).thenReturn(true);

        reconciler.reconcile();

        verify(runSpi, never()).updateRun(any());
        verify(eventPublisher, never()).publishEvent(any(RuntimeEventVO.class));
    }

    @Test
    void runThatJustCompletedIsNotTouched() throws Exception {
        staleCandidates(run(RunStatus.RUNNING.name()));
        when(ownerBucket.isExists()).thenReturn(false);
        when(runSpi.getRunMeta(RUN_ID)).thenReturn(run(RunStatus.COMPLETED.name()));

        reconciler.reconcile();

        verify(runSpi, never()).updateRun(any());
        verify(eventPublisher, never()).publishEvent(any(RuntimeEventVO.class));
    }

    @Test
    void interruptedRunWithoutNewerRun_isQueuedForRecovery() throws Exception {
        RunVO run = run(RunStatus.RUNNING.name());
        when(runSpi.findStaleRunIdsByStatus(eq(RunStatus.RUNNING.name()), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(RUN_ID));
        when(ownerBucket.isExists()).thenReturn(false);
        when(runSpi.getRunMeta(RUN_ID)).thenReturn(run);
        when(runSpi.existsRunAfterId(SESSION_ID, RUN_ID)).thenReturn(false);

        reconciler.reconcile();

        verify(eventPublisher).publishEvent(any(InterruptedRunRecoveryEvent.class));
        verify(runSpi, never()).updateRun(any());
    }

    @Test
    void interruptedRunWithNewerRun_isFailedAsSuperseded() throws Exception {
        RunVO run = run(RunStatus.RUNNING.name());
        when(runSpi.findStaleRunIdsByStatus(eq(RunStatus.RUNNING.name()), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(RUN_ID));
        when(ownerBucket.isExists()).thenReturn(false);
        when(runSpi.getRunMeta(RUN_ID)).thenReturn(run);
        when(runSpi.existsRunAfterId(SESSION_ID, RUN_ID)).thenReturn(true);

        reconciler.reconcile();

        verify(runSpi).updateRun(run);
        assertEquals(RunStatus.FAILED.name(), run.getStatus());
    }

    @Test
    void lockBusySkipsWholeSweep() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        reconciler.reconcile();

        verify(runSpi, never()).findStaleByStatus(anyString(), any(LocalDateTime.class), anyInt());
    }
}

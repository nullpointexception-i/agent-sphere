package com.buukle.agent.runtime.kernel;

import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.instance.dtvo.vo.RunVO;
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
 * 卡在 RUNNING 的收口护栏：OrphanRunSweeper 只认 Redis state=活跃 + 无 owner，
 * 而「循环退出未写终态」的 run 会被置成 state=IDLE + 无 owner —— 它永远扫不到。
 * 本任务按 DB 的 updated_at 兜底，但必须以「无 owner」为前提，避免误杀正在长调用的活 run。
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
        reconciler = new StaleRunningRunReconciler(redissonClient, runSpi, coordinator, eventPublisher, 30);
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
        when(runSpi.getRun(RUN_ID)).thenReturn(run);
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
        when(runSpi.getRun(RUN_ID)).thenReturn(run);
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
        when(runSpi.getRun(RUN_ID)).thenReturn(run(RunStatus.COMPLETED.name()));

        reconciler.reconcile();

        verify(runSpi, never()).updateRun(any());
        verify(eventPublisher, never()).publishEvent(any(RuntimeEventVO.class));
    }

    @Test
    void lockBusySkipsWholeSweep() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        reconciler.reconcile();

        verify(runSpi, never()).findStaleByStatus(anyString(), any(LocalDateTime.class), anyInt());
    }
}

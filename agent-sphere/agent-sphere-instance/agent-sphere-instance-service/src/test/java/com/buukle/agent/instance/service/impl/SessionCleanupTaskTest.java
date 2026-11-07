package com.buukle.agent.instance.service.impl;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.enums.SessionCleanupRunStatusEnum;
import com.buukle.agent.instance.dtvo.enums.SessionCleanupTriggerEnum;
import com.buukle.agent.instance.domain.vo.SessionCleanupRunRowVO;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupRunVO;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupReportVO;
import com.buukle.agent.instance.repository.SessionCleanupMapper;
import com.buukle.agent.instance.repository.SessionCleanupRunMapper;
import com.buukle.agent.tasks.dtvo.enums.TaskEnum;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SessionCleanupTask} unit tests.
 *
 * <p>No DB and no Redis ({@code @ExtendWith(MockitoExtension.class)} + mocks); the focus is on three
 * regression-prone things: whether the active guard really blocks sessions, whether the delete order
 * satisfies the foreign keys, and whether a dry-run really deletes nothing.
 */
@ExtendWith(MockitoExtension.class)
class SessionCleanupTaskTest {

    private static final int BATCH_SIZE = 2;
    private static final int MAX_BATCHES = 5;
    private static final List<String> ACTIVE_RUN_STATUSES =
            List.of(RunEnum.STATUS_PENDING, RunEnum.STATUS_RUNNING, RunEnum.STATUS_WAITING);
    private static final List<String> ACTIVE_TASK_STATUSES =
            List.of(TaskEnum.STATUS_QUEUED, TaskEnum.STATUS_RUNNING);

    @Mock
    private SessionCleanupMapper sessionCleanupMapper;
    @Mock
    private SessionCleanupRunMapper sessionCleanupRunMapper;
    @Mock
    private SystemConfigSpi systemConfigSpi;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private RAtomicLong atomicLong;
    @Mock
    private Executor cleanupExecutor;

    private static final String TRIGGER = SessionCleanupTriggerEnum.TRIGGER_MANUAL;
    private static final Long RECORD_ID = 9001L;

    private SessionCleanupTask task;

    @BeforeEach
    void setUp() {
        task = new SessionCleanupTask(sessionCleanupMapper, sessionCleanupRunMapper, systemConfigSpi,
                redissonClient, cleanupExecutor);
        ReflectionTestUtils.setField(task, "batchSize", BATCH_SIZE);
        ReflectionTestUtils.setField(task, "maxBatches", MAX_BATCHES);
        // Batch sleep set to 0: unit tests should not actually sleep
        ReflectionTestUtils.setField(task, "batchSleepMs", 0);
    }

    /** Clean baseline environment: configuration is normal, the lock can be acquired, and the standalone-category selects all return empty. */
    private void stubHappyPath() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock()).thenReturn(true);
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString()))
                .thenReturn("true");
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS), anyString()))
                .thenReturn("7");
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_FILE_RETENTION_DAYS), anyString()))
                .thenReturn("7");
        lenient().when(sessionCleanupMapper.countActiveBlockedSessions(any(), any(), any())).thenReturn(0L);
        lenient().when(sessionCleanupMapper.countExpiredSessions(any(), any(), any())).thenReturn(0L);
        lenient().when(sessionCleanupMapper.selectOrphanTaskIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanLlmInteractionIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanMemoryIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredFileIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredCompletionsCallIds(any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(sessionCleanupRunMapper.insertRunning(anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyString(), anyString())).thenReturn(RECORD_ID);
        lenient().when(sessionCleanupRunMapper.getRun(eq(RECORD_ID), any(LocalDateTime.class)))
                .thenReturn(new SessionCleanupRunRowVO());
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_LOG_RETENTION_DAYS), anyString()))
                .thenReturn("90");
    }

    /** Make the "expired sessions" query return batch by batch, ending with an empty result to indicate the data is exhausted. */
    @SafeVarargs
    private final void stubExpiredSessions(List<Long>... batches) {
        OngoingStubbing<List<Long>> stubbing = lenient()
                .when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()));
        for (List<Long> batch : batches) {
            stubbing = stubbing.thenReturn(batch);
        }
        stubbing.thenReturn(List.of());
    }

    @Test
    @DisplayName("锁被别的副本/请求持有时直接跳过，且一次 mapper 都不碰")
    void skipsWhenLockBusy() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        SessionCleanupReportVO report = task.runCleanup(false, TRIGGER);

        assertTrue(report.isSkippedByLock());
        assertFalse(report.isEnabled());
        verifyNoInteractions(sessionCleanupMapper);
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("急停开关关掉时不删任何数据，也不释放锁失败")
    void skipsWhenDisabled() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString()))
                .thenReturn("false");

        SessionCleanupReportVO report = task.runCleanup(false, TRIGGER);

        assertFalse(report.isEnabled());
        assertTrue(report.getSkipReason().contains("session.cleanup-enabled"));
        verifyNoInteractions(sessionCleanupMapper);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("dry-run 只统计，一条 DELETE 都不发")
    void dryRunNeverDeletes() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of(10L));
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of(11L));
        when(sessionCleanupMapper.countCascadeBySessionIds(any(), any())).thenReturn(List.of(
                Map.of("table_name", "agent_timeline", "row_count", 5L),
                Map.of("table_name", "agent_run", "row_count", 2L)));

        SessionCleanupReportVO report = task.runCleanup(true, TRIGGER);

        assertEquals(2, report.getSessionCount());
        assertEquals(5L, report.getTableStats().get("agent_timeline"));
        assertEquals(2L, report.getTableStats().get("agent_run"));
        verify(sessionCleanupMapper, never()).deleteSessionByIds(any());
        verify(sessionCleanupMapper, never()).deleteRunBySessionIds(any());
        verify(sessionCleanupMapper, never()).deleteTaskByIds(any());
        verify(sessionCleanupMapper, never()).deleteFilesByIds(any());
    }

    @Test
    @DisplayName("删除顺序：timeline → … → run → task_artifact → task → session（外键约束）")
    void deletesInForeignKeySafeOrder() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of(10L));
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of(11L));
        when(sessionCleanupMapper.deleteTimelineBySessionIds(any())).thenReturn(1);
        when(sessionCleanupMapper.deleteRunBySessionIds(any())).thenReturn(1);
        when(sessionCleanupMapper.deleteTaskArtifactByTaskIds(any())).thenReturn(1);
        when(sessionCleanupMapper.deleteTaskByIds(any())).thenReturn(1);
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(2);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        task.runCleanup(false, TRIGGER);

        InOrder order = inOrder(sessionCleanupMapper);
        order.verify(sessionCleanupMapper).deleteTimelineBySessionIds(List.of(1L, 2L));
        order.verify(sessionCleanupMapper).deleteLlmInteractionBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteToolCallBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteSubAgentRunBySessionIds(any());
        order.verify(sessionCleanupMapper).deletePendingClarificationBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteSessionTodoBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteCompactRecordBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteUserInLoopBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteMemoryBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteDocumentBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteRunBySessionIds(any());
        order.verify(sessionCleanupMapper).deleteTaskArtifactByTaskIds(List.of(10L, 11L));
        order.verify(sessionCleanupMapper).deleteTaskByIds(List.of(10L, 11L));
        order.verify(sessionCleanupMapper).deleteSessionByIds(any());
    }

    @Test
    @DisplayName("没有关联 task 时不发 task 相关 DELETE（IN () 在 PG 是语法错误）")
    void skipsTaskDeletesWhenNoTaskIds() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(1);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        task.runCleanup(false, TRIGGER);

        verify(sessionCleanupMapper, never()).deleteTaskArtifactByTaskIds(any());
        verify(sessionCleanupMapper, never()).deleteTaskByIds(any());
        verify(sessionCleanupMapper).deleteSessionByIds(List.of(1L));
    }

    @Test
    @DisplayName("活跃守卫：状态集合必须是 PENDING/RUNNING/WAITING 与 QUEUED/RUNNING")
    void passesActiveStatusesFromEnums() {
        stubHappyPath();
        stubExpiredSessions(List.of(), List.of());

        task.runCleanup(false, TRIGGER);

        verify(sessionCleanupMapper).selectExpiredSessionIds(any(LocalDateTime.class),
                eq(ACTIVE_RUN_STATUSES), eq(ACTIVE_TASK_STATUSES), eq(BATCH_SIZE));
        verify(sessionCleanupMapper).countActiveBlockedSessions(any(LocalDateTime.class),
                eq(ACTIVE_RUN_STATUSES), eq(ACTIVE_TASK_STATUSES));
    }

    @Test
    @DisplayName("被活跃守卫挡住的会话数要写进报告")
    void reportsActiveBlockedSessions() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString())).thenReturn("true");
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS), anyString()))
                .thenReturn("30");
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_FILE_RETENTION_DAYS), anyString()))
                .thenReturn("14");
        lenient().when(sessionCleanupMapper.countActiveBlockedSessions(any(), any(), any())).thenReturn(17L);
        lenient().when(sessionCleanupMapper.countExpiredSessions(any(), any(), any())).thenReturn(40L);
        lenient().when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanTaskIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanLlmInteractionIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanMemoryIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredFileIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredCompletionsCallIds(any(), anyInt()))
                .thenReturn(List.of());

        SessionCleanupReportVO report = task.runCleanup(true, TRIGGER);

        assertEquals(17L, report.getSkippedActiveSessions());
        assertEquals(30, report.getRetentionDays());
        assertEquals(14, report.getFileRetentionDays());
    }

    @Test
    @DisplayName("配置写错（abc / 0 / 空）时回退默认 7，不能让清理停摆也不能删光")
    void fallsBackToDefaultRetentionDays() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString())).thenReturn("1");
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS), anyString()))
                .thenReturn("abc");
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_FILE_RETENTION_DAYS), anyString()))
                .thenReturn("0");
        lenient().when(sessionCleanupMapper.countActiveBlockedSessions(any(), any(), any())).thenReturn(0L);
        lenient().when(sessionCleanupMapper.countExpiredSessions(any(), any(), any())).thenReturn(0L);
        lenient().when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanTaskIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanLlmInteractionIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectOrphanMemoryIds(any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredFileIds(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionCleanupMapper.selectExpiredCompletionsCallIds(any(), anyInt()))
                .thenReturn(List.of());

        SessionCleanupReportVO report = task.runCleanup(true, TRIGGER);

        assertEquals(7, report.getRetentionDays());
        assertEquals(7, report.getFileRetentionDays());
        assertTrue(report.isEnabled());
    }

    @Test
    @DisplayName("批次上限：每轮都取满时按 max-batches 收口并标记 truncated")
    void stopsAtMaxBatches() {
        stubHappyPath();
        // Every batch is full (size == batchSize) → it never drains, so the loop can only stop via the budget
        when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of(1L, 2L));
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(2);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        SessionCleanupReportVO report = task.runCleanup(false, TRIGGER);

        verify(sessionCleanupMapper, org.mockito.Mockito.times(MAX_BATCHES))
                .deleteSessionByIds(any());
        assertEquals(MAX_BATCHES * BATCH_SIZE, report.getSessionCount());
        assertTrue(report.isTruncated());
    }

    @Test
    @DisplayName("孤儿 task：先删 artifact 再删 task（artifact 是 task 的真外键）")
    void deletesOrphanArtifactsBeforeTasks() {
        stubHappyPath();
        lenient().when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(sessionCleanupMapper.selectOrphanTaskIds(any(), any(), anyInt()))
                .thenReturn(List.of(20L, 21L))
                .thenReturn(List.of());
        when(sessionCleanupMapper.deleteTaskArtifactByTaskIds(any())).thenReturn(2);
        when(sessionCleanupMapper.deleteTaskByIds(any())).thenReturn(2);

        SessionCleanupReportVO report = task.runCleanup(false, TRIGGER);

        InOrder order = inOrder(sessionCleanupMapper);
        order.verify(sessionCleanupMapper).selectOrphanTaskIds(any(), eq(ACTIVE_TASK_STATUSES), eq(BATCH_SIZE));
        order.verify(sessionCleanupMapper).deleteTaskArtifactByTaskIds(List.of(20L, 21L));
        order.verify(sessionCleanupMapper).deleteTaskByIds(List.of(20L, 21L));
        assertEquals(2L, report.getTableStats().get("agent_task"));
    }

    @Test
    @DisplayName("最后一批刚好取满但下次取空时，不应误报 truncated")
    void notTruncatedWhenFullyDrained() {
        stubHappyPath();
        // The first batch exactly equals batchSize (looks like "there is more"), the second is empty → it is actually fully cleaned
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(2);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        SessionCleanupReportVO report = task.runCleanup(false, TRIGGER);

        assertFalse(report.isTruncated());
    }

    @Test
    @DisplayName("删除过程中抛异常时锁仍然释放")
    void releasesLockOnFailure() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteTimelineBySessionIds(any()))
                .thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> task.runCleanup(false, TRIGGER));

        verify(lock).unlock();
    }

    // ==================== Execution record lifecycle ====================

    @Test
    @DisplayName("RUNNING 记录必须在抢锁之前写入")
    void insertsRunningRecordBeforeLocking() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        task.runCleanup(false, TRIGGER);

        InOrder order = inOrder(sessionCleanupRunMapper, redissonClient);
        order.verify(sessionCleanupRunMapper).insertRunning(eq(TRIGGER), eq(false),
                eq(SessionCleanupRunStatusEnum.STATUS_RUNNING), org.mockito.ArgumentMatchers.anyString());
        order.verify(redissonClient).getLock(anyString());
    }

    @Test
    @DisplayName("锁被占用：落 SKIPPED 记录并带原因，即使什么都没删")
    void recordsSkippedWhenLockBusy() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);
        when(sessionCleanupRunMapper.insertRunning(anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyString(), anyString())).thenReturn(RECORD_ID);

        task.runCleanup(false, TRIGGER);

        verify(sessionCleanupRunMapper).markSkipped(eq(RECORD_ID),
                eq(SessionCleanupRunStatusEnum.STATUS_SKIPPED),
                eq(SessionCleanupTask.SKIP_LOCK_REASON),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("急停开关关闭：落 SKIPPED 记录")
    void recordsSkippedWhenDisabled() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(sessionCleanupRunMapper.insertRunning(anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyString(), anyString())).thenReturn(RECORD_ID);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString()))
                .thenReturn("false");

        task.runCleanup(false, TRIGGER);

        verify(sessionCleanupRunMapper).markSkipped(eq(RECORD_ID),
                eq(SessionCleanupRunStatusEnum.STATUS_SKIPPED),
                eq(SessionCleanupTask.SKIP_DISABLED_REASON),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("正常结束：落 SUCCESS 并回填口径、会话数、行数与耗时")
    void recordsSuccessWithStats() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(2);
        when(sessionCleanupMapper.deleteRunBySessionIds(any())).thenReturn(2);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        task.runCleanup(false, TRIGGER);

        verify(sessionCleanupRunMapper).markSuccess(eq(RECORD_ID),
                eq(SessionCleanupRunStatusEnum.STATUS_SUCCESS),
                eq(7), eq(7), any(LocalDateTime.class), any(LocalDateTime.class),
                eq(2), org.mockito.ArgumentMatchers.anyInt(), eq(4L), eq(0L),
                org.mockito.ArgumentMatchers.anyInt(), eq(false), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.contains("agent_session"),
                org.mockito.ArgumentMatchers.contains("VACUUM"), anyString());
    }

    @Test
    @DisplayName("执行中抛异常：落 FAILED 记录，且异常继续上抛不被吞掉")
    void recordsFailedAndRethrows() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteTimelineBySessionIds(any())).thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> task.runCleanup(false, TRIGGER));

        verify(sessionCleanupRunMapper).markFailed(eq(RECORD_ID),
                eq(SessionCleanupRunStatusEnum.STATUS_FAILED), eq("db down"),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
        verify(lock).unlock();
    }

    @Test
    @DisplayName("记录表自身也按保留天数清理，且排除本轮刚写的记录")
    void prunesOwnRunRecords() {
        stubHappyPath();
        stubExpiredSessions(List.of(), List.of());
        when(sessionCleanupRunMapper.deleteOlderThan(any(LocalDateTime.class), any()))
                .thenReturn(3);

        task.runCleanup(false, TRIGGER);

        verify(sessionCleanupRunMapper).deleteOlderThan(any(LocalDateTime.class), eq(RECORD_ID));
    }

    @Test
    @DisplayName("dry-run 时记录表清理只统计不删")
    void dryRunOnlyCountsRunRecords() {
        stubHappyPath();
        stubExpiredSessions(List.of(), List.of());
        when(sessionCleanupRunMapper.countOlderThan(any(LocalDateTime.class), any())).thenReturn(5L);

        task.runCleanup(true, TRIGGER);

        verify(sessionCleanupRunMapper).countOlderThan(any(LocalDateTime.class), eq(RECORD_ID));
        verify(sessionCleanupRunMapper, never()).deleteOlderThan(any(), any());
    }

    @Test
    @DisplayName("执行记录列表：jsonb 文本要被解析成各表行数 Map")
    void listRunsParsesTableStatsJson() {
        SessionCleanupRunRowVO row = new SessionCleanupRunRowVO();
        row.setId(1L);
        row.setTriggerType(TRIGGER);
        row.setDryRun(false);
        row.setStatus(SessionCleanupRunStatusEnum.STATUS_SUCCESS);
        row.setSessionCount(12);
        row.setTotalRows(3456L);
        row.setSkippedActiveSessions(2L);
        row.setTableStats("{\"agent_llm_interaction_record\":3400,\"agent_run\":56}");
        row.setStale(Boolean.FALSE);
        Page<SessionCleanupRunRowVO> rowPage = new Page<>(1, 10, 1);
        rowPage.setRecords(List.of(row));
        when(sessionCleanupRunMapper.pageRuns(any(), any(), any(), any(), any()))
                .thenReturn(rowPage);

        IPage<SessionCleanupRunVO> page = task.listRuns(null, null, null, 1, 10);

        assertEquals(1, page.getTotal());
        SessionCleanupRunVO vo = page.getRecords().get(0);
        assertEquals(3400L, vo.getTableStats().get("agent_llm_interaction_record"));
        assertEquals(56L, vo.getTableStats().get("agent_run"));
        assertEquals(3456L, vo.getTotalRows());
    }

    // ==================== Async submission + live progress ====================

    @Test
    @DisplayName("异步提交：请求线程插记录并立刻返回，后台任务被提交但不阻塞")
    void submitCleanupReturnsImmediatelyWithRunRecord() {
        when(sessionCleanupRunMapper.insertRunning(anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyString(), anyString())).thenReturn(RECORD_ID);
        SessionCleanupRunRowVO row = new SessionCleanupRunRowVO();
        row.setId(RECORD_ID);
        row.setStatus(SessionCleanupRunStatusEnum.STATUS_RUNNING);
        row.setDryRun(true);
        when(sessionCleanupRunMapper.getRun(eq(RECORD_ID), any(LocalDateTime.class))).thenReturn(row);

        SessionCleanupRunVO run = task.submitCleanup(true, TRIGGER);

        assertEquals(RECORD_ID, run.getId());
        assertEquals(SessionCleanupRunStatusEnum.STATUS_RUNNING, run.getStatus());
        // The record is inserted on the request thread (the id returned to the frontend is immediately
        // queryable) and the background task has been submitted
        verify(sessionCleanupRunMapper).insertRunning(eq(TRIGGER), eq(true),
                eq(SessionCleanupRunStatusEnum.STATUS_RUNNING), anyString());
        verify(cleanupExecutor).execute(any(Runnable.class));
        // The submit path must not perform any deletion synchronously
        verify(sessionCleanupMapper, never()).selectExpiredSessionIds(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("进度分母来自执行前的 count，且每批同步一次 markProgress")
    void recordsProgressPerBatch() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of(3L, 4L), List.of());
        when(sessionCleanupMapper.countExpiredSessions(any(), any(), any())).thenReturn(4L);
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteSessionByIds(any())).thenReturn(2);
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        task.runCleanup(false, TRIGGER);

        verify(sessionCleanupRunMapper).markProgress(eq(RECORD_ID), eq(4), eq(2), eq(2L), eq(1),
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
        verify(sessionCleanupRunMapper).markProgress(eq(RECORD_ID), eq(4), eq(4), eq(4L), eq(2),
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
        // The terminal state must carry the denominator as well
        verify(sessionCleanupRunMapper).markSuccess(eq(RECORD_ID),
                eq(SessionCleanupRunStatusEnum.STATUS_SUCCESS),
                eq(7), eq(7), any(LocalDateTime.class), any(LocalDateTime.class),
                eq(4), eq(4), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                eq(false), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("预演不写 VACUUM 建议（没有真的删东西）")
    void dryRunSuccessHasNoVacuumHint() {
        stubHappyPath();
        stubExpiredSessions(List.of(), List.of());

        task.runCleanup(true, TRIGGER);

        verify(sessionCleanupRunMapper).markSuccess(eq(RECORD_ID),
                eq(SessionCleanupRunStatusEnum.STATUS_SUCCESS),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                any(LocalDateTime.class), any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong(), anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString());
    }

    @Test
    @DisplayName("异步执行的异常必须留下完整堆栈日志（没人接异常）")
    void logsStackTraceOnFailure() {
        stubHappyPath();
        stubExpiredSessions(List.of(1L, 2L), List.of());
        when(sessionCleanupMapper.selectTaskIdsBySessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.selectTaskIdsByRunSessionIds(any())).thenReturn(List.of());
        when(sessionCleanupMapper.deleteTimelineBySessionIds(any())).thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> task.runCleanup(false, TRIGGER));

        verify(sessionCleanupRunMapper).markFailed(eq(RECORD_ID),
                eq(SessionCleanupRunStatusEnum.STATUS_FAILED), eq("db down"),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
    }
}

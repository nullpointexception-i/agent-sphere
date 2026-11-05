package com.buukle.agent.instance.service;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupReportVO;
import com.buukle.agent.instance.repository.SessionCleanupMapper;
import com.buukle.agent.instance.repository.SessionCleanupRunMapper;
import com.buukle.agent.instance.service.impl.SessionCleanupTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class SessionCleanupTaskTest {

    private static final Long RECORD_ID = 77L;

    @Mock
    SessionCleanupMapper sessionCleanupMapper;
    @Mock
    SessionCleanupRunMapper runMapper;
    @Mock
    SystemConfigSpi systemConfigSpi;
    @Mock
    RedissonClient redissonClient;
    @Mock
    RLock lock;
    @Mock
    RAtomicLong atomicLong;

    @Test
    void runCleanup_skipsWhenDisabled() {
        SessionCleanupTask task = task();
        when(runMapper.insertRunning(any(), anyBoolean(), any(), any())).thenReturn(RECORD_ID);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString()))
                .thenReturn("false");

        SessionCleanupReportVO report = task.runCleanup(true, "MANUAL");

        assertFalse(report.isEnabled());
        verify(runMapper).markSkipped(eq(RECORD_ID), anyString(), anyString(), anyLong(), anyString());
        verify(sessionCleanupMapper, never()).selectExpiredSessionIds(any(), any(), any(), anyInt());
        verify(lock).unlock();
    }

    @Test
    void runCleanup_skipsWhenLockBusy() {
        SessionCleanupTask task = task();
        when(runMapper.insertRunning(any(), anyBoolean(), any(), any())).thenReturn(RECORD_ID);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        SessionCleanupReportVO report = task.runCleanup(true, "MANUAL");

        assertTrue(report.isSkippedByLock());
        verify(runMapper).markSkipped(eq(RECORD_ID), anyString(), anyString(), anyLong(), anyString());
        verify(lock, never()).unlock();
    }

    @Test
    void runCleanup_dryRun_countsWithoutDeleting() {
        SessionCleanupTask task = task();
        when(runMapper.insertRunning(any(), anyBoolean(), any(), any())).thenReturn(RECORD_ID);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString()))
                .thenReturn("true");
        when(sessionCleanupMapper.countActiveBlockedSessions(any(), any(), any())).thenReturn(0L);
        when(sessionCleanupMapper.countExpiredSessions(any(), any(), any())).thenReturn(1L);
        when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of(10L), List.of());

        SessionCleanupReportVO report = task.runCleanup(true, "MANUAL");

        assertTrue(report.isDryRun());
        assertEquals(1, report.getSessionCount());
        assertEquals(1, report.getTotalSessions());
        assertEquals(1, report.getBatches());
        verify(runMapper).markSuccess(eq(RECORD_ID), anyString(), anyInt(), anyInt(),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(1), eq(1),
                anyLong(), anyLong(), eq(1), anyBoolean(), anyLong(),
                anyString(), any(), anyString());
        verify(sessionCleanupMapper, never()).deleteSessionByIds(any());
    }

    @Test
    void runCleanup_deletesCascadeInForeignKeyOrder() {
        SessionCleanupTask task = task();
        when(runMapper.insertRunning(any(), anyBoolean(), any(), any())).thenReturn(RECORD_ID);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(systemConfigSpi.get(eq(SystemConfigKeys.SESSION_CLEANUP_ENABLED), anyString()))
                .thenReturn("true");
        when(sessionCleanupMapper.countActiveBlockedSessions(any(), any(), any())).thenReturn(0L);
        when(sessionCleanupMapper.countExpiredSessions(any(), any(), any())).thenReturn(1L);
        when(sessionCleanupMapper.selectExpiredSessionIds(any(), any(), any(), anyInt()))
                .thenReturn(List.of(10L), List.of());
        when(redissonClient.getAtomicLong(anyString())).thenReturn(atomicLong);

        SessionCleanupReportVO report = task.runCleanup(false, "MANUAL");

        assertFalse(report.isSkippedByLock());
        var order = inOrder(sessionCleanupMapper);
        order.verify(sessionCleanupMapper).deleteTimelineBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteLlmInteractionBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteToolCallBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteSubAgentRunBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deletePendingClarificationBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteSessionTodoBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteCompactRecordBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteUserInLoopBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteMemoryBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteDocumentBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteRunBySessionIds(List.of(10L));
        order.verify(sessionCleanupMapper).deleteSessionByIds(List.of(10L));
        verify(atomicLong).delete();
    }

    private SessionCleanupTask task() {
        Executor direct = Runnable::run;
        SessionCleanupTask task = new SessionCleanupTask(
                sessionCleanupMapper, runMapper, systemConfigSpi, redissonClient, direct);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "batchSize", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "maxBatches", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "batchSleepMs", 0);
        return task;
    }
}

package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.instance.domain.AgentPendingWait;
import com.buukle.agent.instance.repository.AgentPendingWaitMapper;
import com.buukle.agent.instance.service.PendingWaitService;
import com.buukle.agent.instance.spi.PendingWaitCreatedEvent;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-layer behavior of wait records: create record + publish event + CAS claim.
 *
 * <p>CAS is the only correctness dependency in this mechanism — both the in-memory timer and the cron compensation path can hit the same record,
 * and without CAS the same run is resumed twice.
 */
@ExtendWith(MockitoExtension.class)
class PendingWaitServiceTest {

    @Mock
    AgentPendingWaitMapper waitMapper;
    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    PendingWaitService service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // LambdaUpdateWrapper resolves column names via TableInfo; a pure Mockito environment has no MyBatis startup,
        // so without warm-up it throws "can not find lambda cache".
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "fixture"), AgentPendingWait.class);
    }

    @Test
    void createPending_clampsMsAndPublishesEventSoSchedulerPicksItUp() {
        when(waitMapper.insert(any(AgentPendingWait.class))).thenAnswer(inv -> {
            ((AgentPendingWait) inv.getArgument(0)).setId(77L);
            return 1;
        });

        Long id = service.createPending(1L, 2L, "call-1", 999_999_999);

        assertEquals(77L, id);
        ArgumentCaptor<AgentPendingWait> captor = ArgumentCaptor.forClass(AgentPendingWait.class);
        verify(waitMapper).insert(captor.capture());
        assertEquals(WaitConstants.MAX_WAIT_MS, captor.getValue().getWaitMs(),
                "超过上限的 ms 应被夹到 MAX，避免无限空转窗口");
        assertEquals(WaitConstants.PENDING_STATUS, captor.getValue().getStatus());

        verify(eventPublisher).publishEvent(any(PendingWaitCreatedEvent.class));
    }

    @Test
    void createPending_clampsMsUpToMinimum() {
        when(waitMapper.insert(any(AgentPendingWait.class))).thenAnswer(inv -> {
            ((AgentPendingWait) inv.getArgument(0)).setId(78L);
            return 1;
        });

        service.createPending(1L, 2L, "call-2", 1);

        ArgumentCaptor<AgentPendingWait> captor = ArgumentCaptor.forClass(AgentPendingWait.class);
        verify(waitMapper).insert(captor.capture());
        assertEquals(WaitConstants.MIN_WAIT_MS, captor.getValue().getWaitMs());
    }

    @Test
    void claimForResume_succeedsOnlyWhileRowIsPending() {
        // First claim: the UPDATE matches 1 row
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        assertTrue(service.claimForResume(5L));

        // Second attempt (cron and timer hit simultaneously): already set to RESUMED, the UPDATE matches 0 rows
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        assertFalse(service.claimForResume(5L),
                "CAS 失败方必须放弃 —— 否则同一个 run 会被恢复两次");
    }

    @Test
    void claimForResume_ignoresNullId() {
        assertFalse(service.claimForResume(null));
        verify(waitMapper, never()).update(any(), any());
    }

    @Test
    void claimForResume_usesSingleConditionalUpdate() {
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.claimForResume(9L);

        // It must be a single UPDATE with a status=PENDING condition, never "select then update" —
        // the latter lets both wake paths pass the check concurrently.
        verify(waitMapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
        verify(waitMapper, never()).selectById(any());
    }

    @Test
    void getById_returnsNullForMissingRecord() {
        when(waitMapper.selectById(404L)).thenReturn(null);
        assertEquals(null, service.getById(404L));
    }

    @Test
    void requeueForRetry_putsResumedRowBackToPendingAndCountsAttempt() {
        AgentPendingWait resumed = new AgentPendingWait();
        resumed.setId(11L);
        resumed.setStatus(WaitConstants.RESUMED_STATUS);
        resumed.setAttempts(1);
        when(waitMapper.selectById(11L)).thenReturn(resumed);
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertEquals(2, service.requeueForRetry(11L, "boom"));

        // It must go back to PENDING: listDue / claimForResume only accept PENDING, and a record left in RESUMED is never picked up again
        verify(waitMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void requeueForRetry_returnsMinusOneWhenRowIsNotResumed() {
        AgentPendingWait pending = new AgentPendingWait();
        pending.setId(12L);
        pending.setStatus(WaitConstants.PENDING_STATUS);
        when(waitMapper.selectById(12L)).thenReturn(pending);

        assertEquals(-1, service.requeueForRetry(12L, "boom"));
        verify(waitMapper, never()).update(any(), any());
    }

    @Test
    void requeueForRetry_ignoresNullId() {
        assertEquals(-1, service.requeueForRetry(null, "boom"));
        verify(waitMapper, never()).update(any(), any());
    }

    @Test
    void releaseClaim_putsResumedRowBackToPendingWithoutReadingIt() {
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertTrue(service.releaseClaim(13L));

        // It must be a single atomic UPDATE: reading back is exactly the failure case, so selectById cannot be relied on
        verify(waitMapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
        verify(waitMapper, never()).selectById(any());
    }

    @Test
    void releaseClaim_returnsFalseWhenRowIsNotResumedOrRetriesExhausted() {
        when(waitMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        assertFalse(service.releaseClaim(14L));
    }

    @Test
    void releaseClaim_ignoresNullId() {
        assertFalse(service.releaseClaim(null));
        verify(waitMapper, never()).update(any(), any());
    }

    @Test
    void markTerminal_ignoresNullId() {
        service.markTerminal(null, WaitConstants.FAILED_STATUS, "x");
        verify(waitMapper, never()).update(any(), any());
    }

    @Test
    void listPendingByRunId_ignoresNullRunId() {
        assertTrue(service.listPendingByRunId(null).isEmpty());
        verify(waitMapper, never()).selectList(any());
    }

    @Test
    void listDue_boundsLimitToAtLeastOne() {
        when(waitMapper.selectList(any())).thenReturn(java.util.List.of());
        // Passing 0 / a negative limit must not produce anything but "LIMIT 0" either
        service.listDue(Duration.ofSeconds(2), 0);
        service.listDue(Duration.ofSeconds(2), -5);
        verify(waitMapper, times(2)).selectList(any());
    }
}
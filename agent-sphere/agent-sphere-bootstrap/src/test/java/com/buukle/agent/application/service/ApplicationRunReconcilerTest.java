package com.buukle.agent.application.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.application.domain.BizApplicationRun;
import com.buukle.agent.application.dtvo.enums.ApplicationRunStatus;
import com.buukle.agent.application.repository.BizApplicationRunMapper;
import com.buukle.agent.application.service.impl.ApplicationRunReconciler;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.superagent.spi.WorkItemDispatchSpi;
import com.buukle.agent.superagent.spi.WorkItemStatusSnapshot;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reconciliation maps work-item state onto application runs and retries timeouts. */
@ExtendWith(MockitoExtension.class)
class ApplicationRunReconcilerTest {

    @Mock
    BizApplicationRunMapper runMapper;
    @Mock
    ObjectProvider<WorkItemDispatchSpi> dispatchProvider;
    @Mock
    WorkItemDispatchSpi dispatch;
    @Mock
    AgentMetrics metrics;

    ApplicationRunReconciler reconciler;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                BizApplicationRun.class);
    }

    @BeforeEach
    void setUp() {
        reconciler = new ApplicationRunReconciler(runMapper, dispatchProvider, metrics);
        ReflectionTestUtils.setField(reconciler, "staleMinutes", 10);
        ReflectionTestUtils.setField(reconciler, "maxAttempts", 3);
        when(dispatchProvider.getIfAvailable()).thenReturn(dispatch);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void approvalResume_movesRunBackToRunningWithoutRetry() {
        BizApplicationRun run = run(ApplicationRunStatus.WAITING_APPROVAL, 0);
        when(runMapper.selectList(any(Wrapper.class))).thenReturn(List.of(run));
        when(dispatch.status(7L))
                .thenReturn(new WorkItemStatusSnapshot(7L, "RUNNING", null));

        reconciler.reconcile();

        ArgumentCaptor<BizApplicationRun> captor =
                ArgumentCaptor.forClass(BizApplicationRun.class);
        verify(runMapper).updateById(captor.capture());
        assertEquals(ApplicationRunStatus.RUNNING, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getStartedAt());
        verify(dispatch, never()).retry(7L);
    }

    @Test
    void stuckRunning_requeuesWorkItemAndIncrementsAttempt() {
        BizApplicationRun run = run(ApplicationRunStatus.RUNNING, 1);
        when(runMapper.selectList(any(Wrapper.class))).thenReturn(List.of(run));
        when(dispatch.status(7L))
                .thenReturn(new WorkItemStatusSnapshot(7L, "RUNNING", null));
        when(dispatch.retry(7L)).thenReturn(true);

        reconciler.reconcile();

        verify(dispatch).retry(7L);
        verify(runMapper).update(eq(null), any(Wrapper.class));
        verify(metrics).increment(AgentMetrics.APPLICATION_RUN_RETRY);
    }

    @Test
    void exhaustedAttempts_marksRunFailed() {
        BizApplicationRun run = run(ApplicationRunStatus.QUEUED, 3);
        when(runMapper.selectList(any(Wrapper.class))).thenReturn(List.of(run));
        when(dispatch.status(7L))
                .thenReturn(new WorkItemStatusSnapshot(7L, "QUEUED", null));

        reconciler.reconcile();

        ArgumentCaptor<BizApplicationRun> captor =
                ArgumentCaptor.forClass(BizApplicationRun.class);
        verify(runMapper).updateById(captor.capture());
        assertEquals(ApplicationRunStatus.FAILED, captor.getValue().getStatus());
        verify(dispatch, never()).retry(7L);
    }

    private static BizApplicationRun run(String status, int attempt) {
        BizApplicationRun run = new BizApplicationRun();
        run.setId(11L);
        run.setTenantId(1L);
        run.setWorkItemId(7L);
        run.setApplicationId(2L);
        run.setSceneId(3L);
        run.setStatus(status);
        run.setAttempt(attempt);
        run.setUpdatedAt(LocalDateTime.now().minusMinutes(20));
        return run;
    }
}

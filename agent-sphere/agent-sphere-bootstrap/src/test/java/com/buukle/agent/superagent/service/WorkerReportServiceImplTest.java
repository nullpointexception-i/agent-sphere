package com.buukle.agent.superagent.service;

import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.service.impl.WorkItemCardProjector;
import com.buukle.agent.superagent.service.impl.WorkerReportServiceImpl;
import com.buukle.agent.superagent.spi.WorkerReport;
import com.buukle.agent.superagent.spi.WorkItemLifecycleSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerReportServiceImplTest {

    @Mock
    AgentWorkItemMapper workItemMapper;
    @Mock
    AgentThreadMapper threadMapper;
    @Mock
    AgentEventMapper eventMapper;
    @Mock
    AgentEventDeliveryMapper deliveryMapper;
    @Mock
    WorkItemCardProjector cardProjector;
    @Mock
    RunSpi runSpi;
    @Mock
    UserSpi userSpi;
    @Mock
    AgentMetrics metrics;
    @Mock
    ObjectProvider<WorkItemLifecycleSpi> lifecycleProvider;

    @BeforeEach
    void setUp() {
        lenient().when(lifecycleProvider.orderedStream()).thenReturn(Stream.empty());
    }

    @Test
    void completedReport_updatesWorkItem_andEmitsWorkerReport() {
        WorkerReportServiceImpl service = new WorkerReportServiceImpl(
                workItemMapper, threadMapper, eventMapper, deliveryMapper, cardProjector, runSpi, userSpi, metrics, lifecycleProvider);
        AgentWorkItem item = item(1L, 9L, 8L);
        when(workItemMapper.selectOne(any())).thenReturn(item);
        when(threadMapper.selectById(8L)).thenReturn(new AgentThread());
        when(threadMapper.nextEventSeq(8L)).thenReturn(5L);
        when(eventMapper.insert(any(AgentEvent.class))).thenAnswer(inv -> {
            AgentEvent event = inv.getArgument(0);
            event.setId(100L);
            return 1;
        });

        service.onWorkerTerminal(new WorkerReport(null, 3L, 9L, 11L,
                "COMPLETED", "done", null));

        ArgumentCaptor<AgentWorkItem> itemCaptor = ArgumentCaptor.forClass(AgentWorkItem.class);
        verify(workItemMapper).updateById(itemCaptor.capture());
        assertEquals("DONE", itemCaptor.getValue().getStatus());
        assertEquals("done", itemCaptor.getValue().getSummary());
        verify(cardProjector).projectTerminal(item);
        verify(deliveryMapper).insert(any(AgentEventDelivery.class));
    }

    @Test
    void waitingReport_doesNotProjectCardOrReport() {
        WorkerReportServiceImpl service = new WorkerReportServiceImpl(
                workItemMapper, threadMapper, eventMapper, deliveryMapper, cardProjector, runSpi, userSpi, metrics, lifecycleProvider);
        AgentWorkItem item = item(2L, 10L, null);
        when(workItemMapper.selectOne(any())).thenReturn(item);

        service.onWorkerTerminal(new WorkerReport(null, 3L, 10L, 12L,
                "WAITING", "need input", null));

        verify(cardProjector, never()).projectTerminal(any());
        verify(eventMapper, never()).insert(any(AgentEvent.class));
    }

    @Test
    void blockedReport_marksBlocked_andEmitsReport() {
        WorkerReportServiceImpl service = new WorkerReportServiceImpl(
                workItemMapper, threadMapper, eventMapper, deliveryMapper, cardProjector, runSpi, userSpi, metrics, lifecycleProvider);
        AgentWorkItem item = item(3L, 13L, 8L);
        when(workItemMapper.selectOne(any())).thenReturn(item);
        when(threadMapper.selectById(8L)).thenReturn(new AgentThread());
        when(threadMapper.nextEventSeq(8L)).thenReturn(7L);
        when(eventMapper.insert(any(AgentEvent.class))).thenAnswer(inv -> {
            AgentEvent event = inv.getArgument(0);
            event.setId(101L);
            return 1;
        });

        service.onWorkerTerminal(new WorkerReport(null, 3L, 13L, 21L,
                "BLOCKED", "need approval", null));

        ArgumentCaptor<AgentWorkItem> itemCaptor = ArgumentCaptor.forClass(AgentWorkItem.class);
        verify(workItemMapper).updateById(itemCaptor.capture());
        assertEquals("BLOCKED", itemCaptor.getValue().getStatus());
        verify(cardProjector).projectTerminal(item);
        verify(deliveryMapper).insert(any(AgentEventDelivery.class));
    }

    @Test
    void failedReport_marksFailed_andEmitsReport() {
        WorkerReportServiceImpl service = new WorkerReportServiceImpl(
                workItemMapper, threadMapper, eventMapper, deliveryMapper, cardProjector, runSpi, userSpi, metrics, lifecycleProvider);
        AgentWorkItem item = item(4L, 14L, 8L);
        when(workItemMapper.selectOne(any())).thenReturn(item);
        when(threadMapper.selectById(8L)).thenReturn(new AgentThread());
        when(threadMapper.nextEventSeq(8L)).thenReturn(8L);
        when(eventMapper.insert(any(AgentEvent.class))).thenAnswer(inv -> {
            AgentEvent event = inv.getArgument(0);
            event.setId(102L);
            return 1;
        });

        service.onWorkerTerminal(new WorkerReport(null, 3L, 14L, 22L,
                "FAILED", "boom", "stack"));

        ArgumentCaptor<AgentWorkItem> itemCaptor = ArgumentCaptor.forClass(AgentWorkItem.class);
        verify(workItemMapper).updateById(itemCaptor.capture());
        assertEquals("FAILED", itemCaptor.getValue().getStatus());
        verify(cardProjector).projectTerminal(item);
    }

    @Test
    void cancelledReport_marksCancelled_withoutThreadTouch() {
        WorkerReportServiceImpl service = new WorkerReportServiceImpl(
                workItemMapper, threadMapper, eventMapper, deliveryMapper, cardProjector, runSpi, userSpi, metrics, lifecycleProvider);
        AgentWorkItem item = item(5L, 15L, 8L);
        when(workItemMapper.selectOne(any())).thenReturn(item);
        when(threadMapper.selectById(8L)).thenReturn(new AgentThread());
        when(threadMapper.nextEventSeq(8L)).thenReturn(9L);
        when(eventMapper.insert(any(AgentEvent.class))).thenAnswer(inv -> {
            AgentEvent event = inv.getArgument(0);
            event.setId(103L);
            return 1;
        });

        service.onWorkerTerminal(new WorkerReport(null, 3L, 15L, 23L,
                "CANCELLED", "stopped", null));

        ArgumentCaptor<AgentWorkItem> itemCaptor = ArgumentCaptor.forClass(AgentWorkItem.class);
        verify(workItemMapper).updateById(itemCaptor.capture());
        assertEquals("CANCELLED", itemCaptor.getValue().getStatus());
        verify(threadMapper, never()).updateById(any(AgentThread.class));
        verify(cardProjector).projectTerminal(item);
    }

    private static AgentWorkItem item(Long id, Long workerSessionId, Long threadId) {
        AgentWorkItem item = new AgentWorkItem();
        item.setId(id);
        item.setSuperAgentId(3L);
        item.setWorkerSessionId(workerSessionId);
        item.setThreadId(threadId);
        item.setStatus("RUNNING");
        return item;
    }
}

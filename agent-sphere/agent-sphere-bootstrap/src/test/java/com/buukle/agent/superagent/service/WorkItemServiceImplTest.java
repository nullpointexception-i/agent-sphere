package com.buukle.agent.superagent.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.service.impl.WorkItemServiceImpl;
import com.buukle.agent.superagent.spi.WorkerControlPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkItemServiceImplTest {

    @Mock
    AgentWorkItemMapper workItemMapper;
    @Mock
    AgentSuperAgentMapper superAgentMapper;
    @Mock
    AgentTimelineSpi agentTimelineSpi;
    @Mock
    ObjectProvider<WorkerControlPort> workerControlPortProvider;
    @Mock
    WorkerControlPort workerControlPort;

    WorkItemServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkItemServiceImpl(workItemMapper, superAgentMapper, agentTimelineSpi,
                workerControlPortProvider);
        AuthContext.setUserId(1L);
        AuthContext.setUsername("alice");
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void retry_requeuesFailedItem_andIncrementsAttempt() {
        AgentWorkItem item = item("FAILED", 1);
        when(workItemMapper.selectById(7L)).thenReturn(item);
        when(superAgentMapper.selectById(3L)).thenReturn(agent());

        service.retry(3L, 7L);

        ArgumentCaptor<AgentWorkItem> captor = ArgumentCaptor.forClass(AgentWorkItem.class);
        verify(workItemMapper).updateById(captor.capture());
        assertEquals("QUEUED", captor.getValue().getStatus());
        assertEquals(2, captor.getValue().getAttempt());
        assertEquals(null, captor.getValue().getWorkerSessionId());
    }

    @Test
    void cancel_stopsWorker_andMarksCancelled() {
        AgentWorkItem item = item("RUNNING", 1);
        item.setWorkerSessionId(9L);
        when(workItemMapper.selectById(7L)).thenReturn(item);
        when(superAgentMapper.selectById(3L)).thenReturn(agent());
        when(workerControlPortProvider.getIfAvailable()).thenReturn(workerControlPort);

        service.cancel(3L, 7L);

        verify(workerControlPort).cancel(9L);
        ArgumentCaptor<AgentWorkItem> captor = ArgumentCaptor.forClass(AgentWorkItem.class);
        verify(workItemMapper).updateById(captor.capture());
        assertEquals("CANCELLED", captor.getValue().getStatus());
    }

    private static AgentWorkItem item(String status, int attempt) {
        AgentWorkItem item = new AgentWorkItem();
        item.setId(7L);
        item.setSuperAgentId(3L);
        item.setStatus(status);
        item.setAttempt(attempt);
        return item;
    }

    private static AgentSuperAgent agent() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(3L);
        agent.setOwnerUserId(1L);
        return agent;
    }
}

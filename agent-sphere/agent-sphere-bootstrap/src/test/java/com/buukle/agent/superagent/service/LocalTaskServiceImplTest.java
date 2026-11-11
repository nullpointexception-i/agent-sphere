package com.buukle.agent.superagent.service;

import com.buukle.agent.channel.spi.AgentStreamPort;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentLocalTask;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskCompleteDTO;
import com.buukle.agent.superagent.dtvo.dto.LocalTaskFailDTO;
import com.buukle.agent.superagent.dtvo.enums.LocalTaskStatus;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskAckVO;
import com.buukle.agent.superagent.repository.AgentLocalSessionMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.event.AgentOutboundWakePublisher;
import com.buukle.agent.superagent.service.impl.LocalTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalTaskServiceImplTest {

    private static final Long RUNTIME_ID = 3L;
    private static final Long TASK_ID = 55L;
    private static final Long AGENT_ID = 9L;
    private static final Long THREAD_ID = 77L;

    @Mock
    AgentLocalTaskMapper taskMapper;
    @Mock
    AgentLocalTaskDeliveryMapper taskDeliveryMapper;
    @Mock
    AgentLocalSessionMapper sessionMapper;
    @Mock
    AgentSuperAgentMapper superAgentMapper;
    @Mock
    AgentOutboundMessageMapper outboundMapper;
    @Mock
    AgentOutboundWakePublisher outboundWakePublisher;
    @Mock
    UserSpi userSpi;
    @Mock
    ObjectProvider<AgentStreamPort> streamPortProvider;

    LocalTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LocalTaskServiceImpl(taskMapper, taskDeliveryMapper, sessionMapper,
                superAgentMapper, outboundMapper, outboundWakePublisher, userSpi,
                streamPortProvider);
        ReflectionTestUtils.setField(service, "taskTimeoutSeconds", 600L);
        lenient().when(streamPortProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    void ack_claimsPendingTaskAndReturnsLease() {
        AgentLocalTask task = task(LocalTaskStatus.PENDING, null);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(taskMapper.claim(eq(TASK_ID), eq(RUNTIME_ID), any(), any())).thenReturn(1);
        when(superAgentMapper.selectById(AGENT_ID)).thenReturn(agent());

        LocalTaskAckVO ack = service.ack(RUNTIME_ID, TASK_ID);

        assertNotNull(ack.getLeaseToken());
        assertEquals(TASK_ID, ack.getTask().getTaskId());
        assertEquals(AGENT_ID, ack.getTask().getAgentId());
        verify(taskMapper).claim(eq(TASK_ID), eq(RUNTIME_ID), any(), any());
    }

    @Test
    void complete_insertsOutboundAndMarksDeliveries() {
        AgentLocalTask task = task(LocalTaskStatus.RUNNING, "lease-1");
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);
        when(superAgentMapper.selectById(AGENT_ID)).thenReturn(agent());
        when(outboundMapper.selectOne(any())).thenReturn(null);
        when(outboundMapper.insert(any(AgentOutboundMessage.class))).thenAnswer(invocation -> {
            AgentOutboundMessage message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });
        when(taskMapper.complete(eq(TASK_ID), eq(RUNTIME_ID), eq("lease-1"), eq("codex:thr"),
                eq(99L))).thenReturn(1);
        when(userSpi.getByUserId(7L)).thenReturn(user());

        LocalTaskCompleteDTO dto = new LocalTaskCompleteDTO();
        dto.setLeaseToken("lease-1");
        dto.setText("done");
        dto.setSessionRef("codex:thr");
        service.complete(RUNTIME_ID, TASK_ID, dto);

        ArgumentCaptor<AgentOutboundMessage> captor =
                ArgumentCaptor.forClass(AgentOutboundMessage.class);
        verify(outboundMapper).insert(captor.capture());
        assertEquals("local-" + TASK_ID, captor.getValue().getIdempotencyKey());
        assertEquals(true, captor.getValue().getPayload().contains("done"));
        verify(outboundWakePublisher).publish(99L);
        verify(taskDeliveryMapper).markProcessed(TASK_ID);
        verify(taskMapper).complete(eq(TASK_ID), eq(RUNTIME_ID), eq("lease-1"),
                eq("codex:thr"), eq(99L));
    }

    @Test
    void complete_isIdempotentForSucceededTask() {
        AgentLocalTask task = task(LocalTaskStatus.SUCCEEDED, null);
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        LocalTaskCompleteDTO dto = new LocalTaskCompleteDTO();
        dto.setLeaseToken("anything");
        dto.setText("done");
        service.complete(RUNTIME_ID, TASK_ID, dto);

        verify(outboundMapper, never()).insert(any(AgentOutboundMessage.class));
        verify(taskMapper, never()).complete(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void fail_marksTaskAndDeliveries() {
        AgentLocalTask task = task(LocalTaskStatus.RUNNING, "lease-1");
        when(taskMapper.selectById(TASK_ID)).thenReturn(task);

        LocalTaskFailDTO dto = new LocalTaskFailDTO();
        dto.setErrorCode("CANCELLED");
        dto.setErrorMessage("cancelled by user");
        service.fail(RUNTIME_ID, TASK_ID, dto);

        verify(taskMapper).fail(TASK_ID, RUNTIME_ID, LocalTaskStatus.CANCELLED,
                "CANCELLED", "cancelled by user");
        verify(taskDeliveryMapper).markFailed(eq(TASK_ID), any());
    }

    private static AgentLocalTask task(String status, String leaseToken) {
        AgentLocalTask task = new AgentLocalTask();
        task.setId(TASK_ID);
        task.setRuntimeId(RUNTIME_ID);
        task.setSuperAgentId(AGENT_ID);
        task.setThreadId(THREAD_ID);
        task.setStatus(status);
        task.setLeaseToken(leaseToken);
        task.setAttempts(1);
        return task;
    }

    private static AgentSuperAgent agent() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(AGENT_ID);
        agent.setName("Finance");
        agent.setOwnerUserId(7L);
        agent.setDescription("Handle invoices");
        return agent;
    }

    private static UserVO user() {
        UserVO user = new UserVO();
        user.setId(7L);
        user.setUsername("alice");
        return user;
    }
}

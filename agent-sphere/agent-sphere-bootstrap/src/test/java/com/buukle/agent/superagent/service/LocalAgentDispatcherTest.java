package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentLocalRuntime;
import com.buukle.agent.superagent.domain.AgentLocalTask;
import com.buukle.agent.superagent.domain.AgentLocalTaskDelivery;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentType;
import com.buukle.agent.superagent.dtvo.enums.LocalRuntimeStatus;
import com.buukle.agent.superagent.dtvo.enums.LocalTaskStatus;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskPayloadVO;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentLocalRuntimeMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.impl.LocalAgentDispatcher;
import com.buukle.agent.superagent.service.impl.LocalTaskPayloadBuilder;
import com.buukle.agent.superagent.spi.LocalAgentDispatchCommand;
import com.buukle.agent.superagent.spi.LocalAgentDispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalAgentDispatcherTest {

    private static final Long AGENT_ID = 5L;
    private static final Long THREAD_ID = 77L;
    private static final Long RUNTIME_ID = 3L;
    private static final Long DELIVERY_ID = 10L;
    private static final Long TASK_ID = 55L;

    @Mock
    AgentSuperAgentMapper superAgentMapper;
    @Mock
    AgentLocalRuntimeMapper runtimeMapper;
    @Mock
    AgentEventMapper eventMapper;
    @Mock
    AgentEventDeliveryMapper deliveryMapper;
    @Mock
    AgentLocalTaskMapper taskMapper;
    @Mock
    AgentLocalTaskDeliveryMapper taskDeliveryMapper;
    @Mock
    LocalTaskPayloadBuilder payloadBuilder;
    @Mock
    LocalRuntimeStreamService streamService;

    LocalAgentDispatcher dispatcher;
    AgentSuperAgent agent;
    AgentLocalRuntime runtime;
    AgentEventDelivery delivery;
    AgentEvent event;

    @BeforeEach
    void setUp() {
        dispatcher = new LocalAgentDispatcher(superAgentMapper, runtimeMapper, eventMapper,
                deliveryMapper, taskMapper, taskDeliveryMapper, payloadBuilder, streamService);
        ReflectionTestUtils.setField(dispatcher, "taskTimeoutSeconds", 600L);
        ReflectionTestUtils.setField(dispatcher, "offlineTtlSeconds", 300L);

        agent = new AgentSuperAgent();
        agent.setId(AGENT_ID);
        agent.setTenantId(1L);
        agent.setAgentType(AgentType.LOCAL);
        agent.setLocalRuntimeId(RUNTIME_ID);
        agent.setStatus(SuperAgentStatus.ACTIVE);

        runtime = new AgentLocalRuntime();
        runtime.setId(RUNTIME_ID);
        runtime.setStatus(LocalRuntimeStatus.ACTIVE);
        runtime.setLastSeenAt(LocalDateTime.now());

        delivery = new AgentEventDelivery();
        delivery.setId(DELIVERY_ID);
        delivery.setEventId(20L);
        delivery.setSuperAgentId(AGENT_ID);
        delivery.setStatus(AgentEventDeliveryStatus.PENDING);
        delivery.setAttempts(0);

        event = new AgentEvent();
        event.setId(20L);
        event.setThreadId(THREAD_ID);
        event.setSeq(9L);
        event.setReceivedAt(LocalDateTime.now());

        when(superAgentMapper.selectById(AGENT_ID)).thenReturn(agent);
        lenient().when(runtimeMapper.selectById(RUNTIME_ID)).thenReturn(runtime);
    }

    @Test
    void onlineRuntime_createsTaskAndMovesDeliveries() {
        when(deliveryMapper.selectBatchIds(List.of(DELIVERY_ID))).thenReturn(List.of(delivery));
        LocalTaskPayloadVO payload = payload();
        when(payloadBuilder.build(agent, THREAD_ID, List.of(delivery), 600L)).thenReturn(payload);
        when(taskMapper.insert(any(AgentLocalTask.class))).thenAnswer(invocation -> {
            AgentLocalTask task = invocation.getArgument(0);
            task.setId(TASK_ID);
            return 1;
        });

        LocalAgentDispatchResult result = dispatcher.dispatch(
                new LocalAgentDispatchCommand(AGENT_ID, THREAD_ID, List.of(DELIVERY_ID)));

        assertTrue(result.accepted());
        assertEquals(TASK_ID, result.taskId());
        ArgumentCaptor<AgentLocalTask> taskCaptor = ArgumentCaptor.forClass(AgentLocalTask.class);
        verify(taskMapper).insert(taskCaptor.capture());
        assertEquals(LocalTaskStatus.PENDING, taskCaptor.getValue().getStatus());
        assertEquals(RUNTIME_ID, taskCaptor.getValue().getRuntimeId());
        assertEquals(TASK_ID, taskCaptor.getValue().getId());

        ArgumentCaptor<AgentEventDelivery> deliveryCaptor =
                ArgumentCaptor.forClass(AgentEventDelivery.class);
        verify(deliveryMapper).updateById(deliveryCaptor.capture());
        assertEquals(AgentEventDeliveryStatus.RUNNING, deliveryCaptor.getValue().getStatus());
        assertEquals(TASK_ID, deliveryCaptor.getValue().getTargetTaskId());

        verify(taskDeliveryMapper).insert(any(AgentLocalTaskDelivery.class));
        verify(streamService).publishTaskReady(RUNTIME_ID, TASK_ID);
    }

    @Test
    void offlineRuntime_backsOffWithoutCreatingTask() {
        runtime.setLastSeenAt(LocalDateTime.now().minusMinutes(10));
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(delivery);
        when(eventMapper.selectById(20L)).thenReturn(event);

        LocalAgentDispatchResult result = dispatcher.dispatch(
                new LocalAgentDispatchCommand(AGENT_ID, THREAD_ID, List.of(DELIVERY_ID)));

        assertFalse(result.accepted());
        verify(deliveryMapper).backoffPending(eq(DELIVERY_ID), any(), eq("local runtime offline"));
        verify(taskMapper, never()).insert(any(AgentLocalTask.class));
    }

    @Test
    void offlineRuntime_failsExpiredDelivery() {
        runtime.setLastSeenAt(LocalDateTime.now().minusMinutes(10));
        event.setReceivedAt(LocalDateTime.now().minusMinutes(10));
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(delivery);
        when(eventMapper.selectById(20L)).thenReturn(event);

        dispatcher.dispatch(new LocalAgentDispatchCommand(
                AGENT_ID, THREAD_ID, List.of(DELIVERY_ID)));

        verify(deliveryMapper).failPending(DELIVERY_ID, "local runtime offline beyond ttl");
        verify(taskMapper, never()).insert(any(AgentLocalTask.class));
    }

    @Test
    void cloudAgent_isRejected() {
        agent.setAgentType(AgentType.CLOUD);

        LocalAgentDispatchResult result = dispatcher.dispatch(
                new LocalAgentDispatchCommand(AGENT_ID, THREAD_ID, List.of(DELIVERY_ID)));

        assertFalse(result.accepted());
        verify(deliveryMapper).failPending(DELIVERY_ID,
                "local agent or runtime is not configured");
        verify(taskMapper, never()).insert(any(AgentLocalTask.class));
    }

    private static LocalTaskPayloadVO payload() {
        LocalTaskPayloadVO payload = new LocalTaskPayloadVO();
        LocalTaskPayloadVO.Trigger trigger = new LocalTaskPayloadVO.Trigger();
        trigger.setSeqTo(9L);
        payload.setTrigger(trigger);
        return payload;
    }
}

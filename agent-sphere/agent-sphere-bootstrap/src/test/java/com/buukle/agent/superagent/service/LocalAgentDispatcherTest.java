package com.buukle.agent.superagent.service;

import com.buukle.agent.superagent.domain.AgentLocalRuntime;
import com.buukle.agent.superagent.domain.AgentLocalTask;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.enums.AgentType;
import com.buukle.agent.superagent.dtvo.enums.LocalRuntimeStatus;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskPayloadVO;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentLocalRuntimeMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.impl.LocalAgentDispatcher;
import com.buukle.agent.superagent.service.impl.LocalTaskPayloadBuilder;
import com.buukle.agent.superagent.spi.LocalProjectMentionCommand;
import com.buukle.agent.superagent.spi.LocalProjectMentionDispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalAgentDispatcherTest {

    private static final Long AGENT_ID = 14L;
    private static final Long RUNTIME_ID = 72L;
    private static final Long MENTION_ID = 123L;

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
    com.buukle.agent.superagent.service.LocalRuntimeStreamService streamService;

    LocalAgentDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new LocalAgentDispatcher(superAgentMapper, runtimeMapper, eventMapper,
                deliveryMapper, taskMapper, taskDeliveryMapper, payloadBuilder, streamService);
        ReflectionTestUtils.setField(dispatcher, "taskTimeoutSeconds", 600L);
        ReflectionTestUtils.setField(dispatcher, "offlineTtlSeconds", 300L);
    }

    @Test
    void dispatch_createsLocalTaskForOnlineRuntime() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(AGENT_ID);
        agent.setTenantId(2L);
        agent.setAgentType(AgentType.LOCAL);
        agent.setLocalRuntimeId(RUNTIME_ID);
        agent.setName("code-agent");
        when(superAgentMapper.selectById(AGENT_ID)).thenReturn(agent);
        when(runtimeMapper.selectById(RUNTIME_ID)).thenReturn(runtime());
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(payloadBuilder.buildProjectMention(any(), any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new LocalTaskPayloadVO());
        when(taskMapper.insert(any(AgentLocalTask.class))).thenAnswer(invocation -> {
            AgentLocalTask task = invocation.getArgument(0);
            task.setId(456L);
            return 1;
        });

        LocalProjectMentionDispatchResult result = dispatcher.dispatch(command());

        assertTrue(result.accepted());
        assertEquals(456L, result.taskId());
        ArgumentCaptor<AgentLocalTask> captor = ArgumentCaptor.forClass(AgentLocalTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals("PROJECT_MENTION", captor.getValue().getBusinessType());
        assertEquals(MENTION_ID, captor.getValue().getBusinessId());
        verify(streamService).publishTaskReady(RUNTIME_ID, 456L);
    }

    @Test
    void dispatch_defersWhenRuntimeOffline() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(AGENT_ID);
        agent.setTenantId(2L);
        agent.setAgentType(AgentType.LOCAL);
        agent.setLocalRuntimeId(RUNTIME_ID);
        when(superAgentMapper.selectById(AGENT_ID)).thenReturn(agent);
        when(runtimeMapper.selectById(RUNTIME_ID)).thenReturn(runtimeOffline());

        LocalProjectMentionDispatchResult result = dispatcher.dispatch(command());

        assertFalse(result.accepted());
        assertTrue(result.retryable());
        verify(taskMapper, never()).insert(any(AgentLocalTask.class));
    }

    private static AgentLocalRuntime runtime() {
        AgentLocalRuntime runtime = new AgentLocalRuntime();
        runtime.setId(RUNTIME_ID);
        runtime.setStatus(LocalRuntimeStatus.ACTIVE);
        runtime.setLastSeenAt(LocalDateTime.now().minusSeconds(30));
        return runtime;
    }

    private static AgentLocalRuntime runtimeOffline() {
        AgentLocalRuntime runtime = runtime();
        runtime.setLastSeenAt(LocalDateTime.now().minusMinutes(10));
        return runtime;
    }

    private static LocalProjectMentionCommand command() {
        return new LocalProjectMentionCommand(2L, AGENT_ID, 2L, "elvin", 2L, 1L, 6L,
                MENTION_ID, 99L, "REQ-0001", "Please analyze the risk");
    }
}

package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.sso.spi.CallerAuth;
import com.buukle.agent.sso.spi.ResolvedIdentityVO;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.tasks.domain.AgentTask;
import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskVO;
import com.buukle.agent.tasks.repository.AgentTaskMapper;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.tasks.service.impl.AgentTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentTaskServiceTest {

    private static final CallerAuth AUTH = CallerAuth.of("bole", "elvin", "sourcing");

    @Mock
    AgentTaskMapper taskMapper;
    @Mock
    InstanceSpi instanceSpi;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    RunSpi runSpi;
    @Mock
    ChatRuntimeService chatRuntimeService;
    @Mock
    TaskCallbackService taskCallbackService;
    @Mock
    SsoIdentitySpi ssoIdentitySpi;

    @InjectMocks
    AgentTaskServiceImpl taskService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(taskService, "pollInterval", Duration.ofDays(1));
    }

    private void stubIdentity() {
        given(ssoIdentitySpi.resolveByCodeSubject("bole", "elvin"))
                .willReturn(ResolvedIdentityVO.of(1L, "elvin", "Elvin"));
    }

    private InstanceVO instance(long id) {
        InstanceVO instance = new InstanceVO();
        instance.setId(id);
        instance.setStatus("ENABLED");
        instance.setBusinessType("sourcing");
        instance.setCreatedBy("elvin");
        return instance;
    }

    @Test
    void submit_shouldPersistResolveInstanceAndStartRun() {
        stubIdentity();
        given(instanceSpi.listInstances(any(), any(), any())).willReturn(List.of(instance(2L)));

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("整理月报");
        dto.setContext(Map.of("month", "2026-04"));
        dto.setCode("bole");
        dto.setSubject("elvin");
        dto.setBusinessType("sourcing");

        SessionVO session = new SessionVO();
        session.setId(11L);
        given(sessionSpi.createSession(any(CreateSessionDTO.class))).willReturn(session);

        ChatMessageResponseVO chatResp = new ChatMessageResponseVO();
        chatResp.setRunId(22L);
        given(chatRuntimeService.chat(anyLong(), any(SendMessageDTO.class))).willReturn(chatResp);

        given(taskMapper.insert(any(AgentTask.class))).willAnswer(inv -> {
            inv.<AgentTask>getArgument(0).setId(1L);
            return 1;
        });

        TaskVO vo = taskService.submit(dto, AUTH);

        assertNotNull(vo);
        assertEquals("RUNNING", vo.getStatus());
        assertEquals(2L, vo.getInstanceId());
        assertEquals(11L, vo.getSessionId());
        assertEquals(22L, vo.getRunId());

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        AgentTask saved = taskCaptor.getValue();
        assertEquals("RUNNING", saved.getStatus());
        assertEquals("{\"month\":\"2026-04\"}", saved.getContextJson());
        assertEquals("elvin", saved.getCreatedBy());
    }

    @Test
    void submit_withoutEnabledBusinessInstance_shouldThrow() {
        stubIdentity();
        given(instanceSpi.listInstances(any(), any(), any())).willReturn(List.of());

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("写总结");
        dto.setCode("bole");
        dto.setSubject("elvin");
        dto.setBusinessType("sourcing");

        assertThrows(BizException.class, () -> taskService.submit(dto, AUTH));
    }

    @Test
    void submit_invalidIdentity_shouldThrow() {
        given(ssoIdentitySpi.resolveByCodeSubject("bole", "elvin")).willReturn(null);

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("写总结");
        dto.setCode("bole");
        dto.setSubject("elvin");
        dto.setBusinessType("sourcing");

        assertThrows(BizException.class, () -> taskService.submit(dto, AUTH));
    }

    @Test
    void stop_shouldCancelRunningTask() {
        stubIdentity();
        AgentTask task = new AgentTask();
        task.setId(7L);
        task.setStatus("RUNNING");
        task.setCreatedBy("elvin");
        task.setInstanceId(2L);
        task.setSessionId(11L);
        task.setRunId(22L);
        given(taskMapper.selectById(7L)).willReturn(task);
        given(instanceSpi.getInstance(2L)).willReturn(instance(2L));

        taskService.stop(7L, AUTH);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals("CANCELLED", captor.getValue().getStatus());
        verify(chatRuntimeService).stopRun(11L, 22L);
        verify(taskCallbackService).notifyTerminal(any(AgentTask.class));
    }

    @Test
    void stop_otherUsersTask_shouldThrow() {
        stubIdentity();
        AgentTask task = new AgentTask();
        task.setId(7L);
        task.setStatus("RUNNING");
        task.setCreatedBy("someone-else");
        given(taskMapper.selectById(7L)).willReturn(task);

        assertThrows(BizException.class, () -> taskService.stop(7L, AUTH));
    }

    @Test
    void submit_shouldSendAutonomousMessageWithoutClarification() {
        stubIdentity();
        given(instanceSpi.listInstances(any(), any(), any())).willReturn(List.of(instance(2L)));

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("整理月报");
        dto.setCode("bole");
        dto.setSubject("elvin");
        dto.setBusinessType("sourcing");

        SessionVO session = new SessionVO();
        session.setId(11L);
        given(sessionSpi.createSession(any(CreateSessionDTO.class))).willReturn(session);

        ChatMessageResponseVO chatResp = new ChatMessageResponseVO();
        chatResp.setRunId(22L);
        given(chatRuntimeService.chat(anyLong(), any(SendMessageDTO.class))).willReturn(chatResp);

        given(taskMapper.insert(any(AgentTask.class))).willAnswer(inv -> {
            inv.<AgentTask>getArgument(0).setId(1L);
            return 1;
        });

        taskService.submit(dto, AUTH);

        ArgumentCaptor<SendMessageDTO> messageCaptor = ArgumentCaptor.forClass(SendMessageDTO.class);
        verify(chatRuntimeService).chat(anyLong(), messageCaptor.capture());
        SendMessageDTO sent = messageCaptor.getValue();
        assertEquals(Boolean.TRUE, sent.getNoClarification());
        org.junit.jupiter.api.Assertions.assertTrue(sent.getMessage().contains("整理月报"));
        org.junit.jupiter.api.Assertions.assertTrue(sent.getMessage().contains("禁止向用户提问"));
    }

    @Test
    void submit_shouldPersistCallbackUrl() {
        stubIdentity();
        given(instanceSpi.listInstances(any(), any(), any())).willReturn(List.of(instance(2L)));

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("寻访任务");
        dto.setCode("bole");
        dto.setSubject("elvin");
        dto.setBusinessType("sourcing");
        dto.setCallbackUrl("https://callback.example/api/v1/tasks/callback");

        SessionVO session = new SessionVO();
        session.setId(11L);
        given(sessionSpi.createSession(any(CreateSessionDTO.class))).willReturn(session);

        ChatMessageResponseVO chatResp = new ChatMessageResponseVO();
        chatResp.setRunId(22L);
        given(chatRuntimeService.chat(anyLong(), any(SendMessageDTO.class))).willReturn(chatResp);

        given(taskMapper.insert(any(AgentTask.class))).willAnswer(inv -> {
            inv.<AgentTask>getArgument(0).setId(1L);
            return 1;
        });

        taskService.submit(dto, AUTH);

        ArgumentCaptor<AgentTask> insertCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).insert(insertCaptor.capture());
        AgentTask inserted = insertCaptor.getValue();
        assertEquals("elvin", inserted.getCreatedBy());
        assertEquals("https://callback.example/api/v1/tasks/callback", inserted.getCallbackUrl());
    }

    @Test
    void get_notFound_shouldThrow() {
        given(taskMapper.selectById(99L)).willReturn(null);

        assertThrows(BizException.class, () -> taskService.get(99L, AUTH));
    }

    @Test
    void get_notOwner_shouldThrow() {
        stubIdentity();
        AgentTask task = new AgentTask();
        task.setId(7L);
        task.setCreatedBy("someone-else");
        given(taskMapper.selectById(7L)).willReturn(task);

        assertThrows(BizException.class, () -> taskService.get(7L, AUTH));
    }

    @Test
    void submit_chatThrows_shouldMarkFailedAndRethrow() {
        stubIdentity();
        given(instanceSpi.listInstances(any(), any(), any())).willReturn(List.of(instance(2L)));

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("整理月报");
        dto.setCode("bole");
        dto.setSubject("elvin");
        dto.setBusinessType("sourcing");

        SessionVO session = new SessionVO();
        session.setId(11L);
        given(sessionSpi.createSession(any(CreateSessionDTO.class))).willReturn(session);

        given(chatRuntimeService.chat(anyLong(), any(SendMessageDTO.class)))
                .willThrow(new RuntimeException("boom"));

        given(taskMapper.insert(any(AgentTask.class))).willAnswer(inv -> {
            inv.<AgentTask>getArgument(0).setId(1L);
            return 1;
        });
        AgentTask persisted = new AgentTask();
        persisted.setId(1L);
        persisted.setStatus("QUEUED");
        given(taskMapper.selectById(1L)).willReturn(persisted);

        assertThrows(RuntimeException.class, () -> taskService.submit(dto, AUTH));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        AgentTask failed = taskCaptor.getValue();
        assertEquals("FAILED", failed.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(failed.getResultJson().contains("boom"));
    }
}

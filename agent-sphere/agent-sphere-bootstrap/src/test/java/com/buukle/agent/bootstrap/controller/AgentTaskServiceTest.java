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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentTaskServiceTest {

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

    @InjectMocks
    AgentTaskServiceImpl taskService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(taskService, "pollInterval", Duration.ofDays(1));
    }

    @Test
    void submit_shouldPersistResolveInstanceAndStartRun() {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("整理月报");
        dto.setContext(Map.of("month", "2026-04"));
        dto.setInstanceId(2L);

        InstanceVO instance = new InstanceVO();
        instance.setId(2L);
        instance.setStatus("ENABLED");
        given(instanceSpi.getInstance(2L)).willReturn(instance);

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

        TaskVO vo = taskService.submit(dto);

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
    }

    @Test
    void submit_withoutInstanceId_shouldPickFirstEnabled() {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("写总结");

        InstanceVO a = new InstanceVO();
        a.setId(1L);
        a.setStatus("DISABLED");
        InstanceVO b = new InstanceVO();
        b.setId(5L);
        b.setStatus("ENABLED");
        given(instanceSpi.listInstances(any(), any(), any())).willReturn(List.of(a, b));

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

        TaskVO vo = taskService.submit(dto);

        assertEquals(5L, vo.getInstanceId());
    }

    @Test
    void stop_shouldCancelRunningTask() {
        AgentTask task = new AgentTask();
        task.setId(7L);
        task.setStatus("RUNNING");
        task.setSessionId(11L);
        task.setRunId(22L);
        given(taskMapper.selectById(7L)).willReturn(task);

        taskService.stop(7L);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals("CANCELLED", captor.getValue().getStatus());
        verify(chatRuntimeService).stopRun(11L, 22L);
        verify(taskCallbackService).notifyTerminal(any(AgentTask.class));
    }

    @Test
    void submit_shouldSendAutonomousMessageWithoutClarification() {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("整理月报");
        dto.setInstanceId(2L);

        InstanceVO instance = new InstanceVO();
        instance.setId(2L);
        instance.setStatus("ENABLED");
        given(instanceSpi.getInstance(2L)).willReturn(instance);

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

        taskService.submit(dto);

        ArgumentCaptor<SendMessageDTO> messageCaptor = ArgumentCaptor.forClass(SendMessageDTO.class);
        verify(chatRuntimeService).chat(anyLong(), messageCaptor.capture());
        SendMessageDTO sent = messageCaptor.getValue();
        assertEquals(Boolean.TRUE, sent.getNoClarification());
        org.junit.jupiter.api.Assertions.assertTrue(sent.getMessage().contains("整理月报"));
        org.junit.jupiter.api.Assertions.assertTrue(sent.getMessage().contains("禁止向用户提问"));
    }

    @Test
    void submit_shouldResolveCallerFromInstanceAndPersistCallbackUrl() {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("寻访任务");
        dto.setInstanceId(2L);
        dto.setCallbackUrl("https://callback.example/api/v1/tasks/callback");

        InstanceVO instance = new InstanceVO();
        instance.setId(2L);
        instance.setStatus("ENABLED");
        instance.setCreatedBy("creator-a");
        given(instanceSpi.getInstance(2L)).willReturn(instance);

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

        taskService.submit(dto);

        ArgumentCaptor<AgentTask> insertCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).insert(insertCaptor.capture());
        AgentTask inserted = insertCaptor.getValue();
        assertEquals("creator-a", inserted.getCreatedBy());
        assertEquals("https://callback.example/api/v1/tasks/callback", inserted.getCallbackUrl());
    }

    @Test
    void get_notFound_shouldThrow() {
        given(taskMapper.selectById(99L)).willReturn(null);

        assertThrows(BizException.class, () -> taskService.get(99L));
    }

    @Test
    void submit_chatThrows_shouldMarkFailedAndRethrow() {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setGoal("整理月报");
        dto.setInstanceId(2L);

        InstanceVO instance = new InstanceVO();
        instance.setId(2L);
        instance.setStatus("ENABLED");
        given(instanceSpi.getInstance(2L)).willReturn(instance);

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

        assertThrows(RuntimeException.class, () -> taskService.submit(dto));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        AgentTask failed = taskCaptor.getValue();
        assertEquals("FAILED", failed.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(failed.getResultJson().contains("boom"));
    }
}

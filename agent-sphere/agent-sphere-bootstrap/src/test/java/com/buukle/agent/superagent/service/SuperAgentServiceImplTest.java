package com.buukle.agent.superagent.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.dict.SystemDicConstants;
import com.buukle.agent.common.dict.SystemDicEntry;
import com.buukle.agent.common.dict.SystemDicSpi;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.dto.CreateSuperAgentDTO;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentVO;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.impl.SuperAgentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAgentServiceImplTest {

    @Mock
    AgentSuperAgentMapper superAgentMapper;
    @Mock
    InstanceSpi instanceSpi;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    AgentRouterPromptService agentRouterPromptService;
    @Mock
    RouteSpi routeSpi;
    @Mock
    SystemDicSpi systemDicSpi;

    SuperAgentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SuperAgentServiceImpl(superAgentMapper, instanceSpi, sessionSpi,
                agentRouterPromptService, routeSpi, systemDicSpi);
        ReflectionTestUtils.setField(service, "routerMaxLoopCount", 64);
        ReflectionTestUtils.setField(service, "workerDefaultLoopLimit", 128);
        AuthContext.setUserId(1L);
        AuthContext.setUsername("alice");
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void create_provisionsRouterWorkerAndGuardianSession() {
        when(superAgentMapper.insert(any(AgentSuperAgent.class))).thenAnswer(invocation -> {
            AgentSuperAgent agent = invocation.getArgument(0);
            agent.setId(7L);
            return 1;
        });
        when(agentRouterPromptService.resolve()).thenReturn("global router prompt");
        when(systemDicSpi.findActive(SystemDicConstants.TYPE_POSITION, "FINANCE"))
                .thenReturn(new SystemDicEntry("FINANCE", "财务专家", "Finance Specialist"));
        ModelRouteVO defaultRoute = new ModelRouteVO();
        defaultRoute.setId(5L);
        when(routeSpi.getDefaultRoute()).thenReturn(defaultRoute);
        InstanceVO router = instance(11L);
        InstanceVO worker = instance(12L);
        when(instanceSpi.createInstance(any())).thenReturn(router, worker);
        SessionVO session = new SessionVO();
        session.setId(21L);
        when(sessionSpi.createSession(any())).thenReturn(session);

        CreateSuperAgentDTO dto = new CreateSuperAgentDTO();
        dto.setName("Support");
        dto.setPositionCode("FINANCE");

        SuperAgentVO result = service.create(dto);

        ArgumentCaptor<CreateInstanceDTO> instances = ArgumentCaptor.forClass(CreateInstanceDTO.class);
        verify(instanceSpi, times(2)).createInstance(instances.capture());
        List<CreateInstanceDTO> created = instances.getAllValues();
        CreateInstanceDTO routerDTO = created.get(0);
        CreateInstanceDTO workerDTO = created.get(1);
        assertEquals("ROUTER", routerDTO.getInstanceRole());
        assertEquals("global router prompt\n\nSTRICT: You may only reply to requests related to "
                + "Finance Specialist. If a request is unrelated, decline it briefly.",
                routerDTO.getSystemPrompt());
        assertEquals(5L, routerDTO.getModelRouteId());
        assertEquals(64, routerDTO.getMaxLoopCount());
        assertFalse(routerDTO.getAllowConsoleSession());
        assertFalse(routerDTO.getAllowWidgetSession());
        assertEquals("WORKER", workerDTO.getInstanceRole());
        assertEquals("STRICT: You may only reply to requests related to Finance Specialist. "
                + "If a request is unrelated, decline it briefly.", workerDTO.getSystemPrompt());
        assertEquals(5L, workerDTO.getModelRouteId());
        assertEquals(128, workerDTO.getMaxLoopCount());
        assertFalse(workerDTO.getAllowConsoleSession());
        assertFalse(workerDTO.getAllowWidgetSession());

        ArgumentCaptor<CreateSessionDTO> sessions = ArgumentCaptor.forClass(CreateSessionDTO.class);
        verify(sessionSpi).createSession(sessions.capture());
        CreateSessionDTO sessionDTO = sessions.getValue();
        assertEquals(11L, sessionDTO.getAgentInstanceId());
        assertEquals("ROUTER", sessionDTO.getSessionType());
        assertEquals(7L, sessionDTO.getSuperAgentId());
        assertEquals("alice", sessionDTO.getCreatedBy());
        assertTrue(sessionDTO.getTitle().contains("守护会话"));

        ArgumentCaptor<AgentSuperAgent> updated = ArgumentCaptor.forClass(AgentSuperAgent.class);
        verify(superAgentMapper).updateById(updated.capture());
        assertEquals(11L, updated.getValue().getRouterInstanceId());
        assertEquals(21L, updated.getValue().getRouterSessionId());
        assertEquals(12L, updated.getValue().getWorkerInstanceId());

        assertEquals(11L, result.getRouterInstanceId());
        assertEquals(21L, result.getRouterSessionId());
        assertEquals(12L, result.getWorkerInstanceId());
    }

    @Test
    void create_rejectsUnknownPosition() {
        ModelRouteVO defaultRoute = new ModelRouteVO();
        defaultRoute.setId(5L);
        when(routeSpi.getDefaultRoute()).thenReturn(defaultRoute);
        when(systemDicSpi.findActive(SystemDicConstants.TYPE_POSITION, "NOPE")).thenReturn(null);
        CreateSuperAgentDTO dto = new CreateSuperAgentDTO();
        dto.setName("Support");
        dto.setPositionCode("NOPE");

        BizException error = assertThrows(BizException.class, () -> service.create(dto));

        assertEquals(CommonErrorCode.PARAM_INVALID.getCode(), error.getErrorCode());
        verify(superAgentMapper, times(0)).insert(any(AgentSuperAgent.class));
    }

    @Test
    void getInstances_returnsRouterWorkerAndGuardianSession() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(7L);
        agent.setOwnerUserId(1L);
        agent.setRouterInstanceId(11L);
        agent.setWorkerInstanceId(12L);
        agent.setRouterSessionId(21L);
        when(superAgentMapper.selectById(7L)).thenReturn(agent);
        InstanceVO router = instance(11L);
        InstanceVO worker = instance(12L);
        when(instanceSpi.getInstance(11L)).thenReturn(router);
        when(instanceSpi.getInstance(12L)).thenReturn(worker);
        SessionVO session = new SessionVO();
        session.setId(21L);
        when(sessionSpi.getSession(21L)).thenReturn(session);

        var result = service.getInstances(7L);

        assertEquals(7L, result.getSuperAgentId());
        assertEquals(11L, result.getRouterInstance().getId());
        assertEquals(12L, result.getWorkerInstance().getId());
        assertEquals(21L, result.getGuardianSession().getId());
    }

    private static InstanceVO instance(Long id) {
        InstanceVO vo = new InstanceVO();
        vo.setId(id);
        return vo;
    }
}

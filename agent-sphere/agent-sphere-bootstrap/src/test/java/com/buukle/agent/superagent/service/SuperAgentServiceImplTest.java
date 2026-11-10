package com.buukle.agent.superagent.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.dict.SystemDicConstants;
import com.buukle.agent.common.dict.SystemDicEntry;
import com.buukle.agent.common.dict.SystemDicSpi;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.event.InstanceDeletedEvent;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.dto.CreateSuperAgentDTO;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.dtvo.vo.SuperAgentVO;
import com.buukle.agent.superagent.repository.AgentLocalRuntimeMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    AgentRouterPromptService agentRouterPromptService;
    @Mock
    RouteSpi routeSpi;
    @Mock
    SystemDicSpi systemDicSpi;
    @Mock
    AgentLocalRuntimeMapper localRuntimeMapper;

    SuperAgentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SuperAgentServiceImpl(superAgentMapper, instanceSpi,
                agentRouterPromptService, routeSpi, systemDicSpi, localRuntimeMapper);
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
    void create_provisionsRouterAndWorker() {
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

        CreateSuperAgentDTO dto = new CreateSuperAgentDTO();
        dto.setName("Support");
        dto.setPositionCode("FINANCE");
        dto.setDescription("Handle invoices and budgets");

        SuperAgentVO result = service.create(dto);

        ArgumentCaptor<CreateInstanceDTO> instances = ArgumentCaptor.forClass(CreateInstanceDTO.class);
        verify(instanceSpi, times(2)).createInstance(instances.capture());
        List<CreateInstanceDTO> created = instances.getAllValues();
        CreateInstanceDTO routerDTO = created.get(0);
        CreateInstanceDTO workerDTO = created.get(1);
        assertEquals("ROUTER", routerDTO.getInstanceRole());
        assertEquals("You are a Finance Specialist. Use the available tools and skills to help "
                + "the user achieve their goals. Your job scope is Handle invoices and budgets."
                + "\n\nglobal router prompt\n\nSTRICT: You may only reply to requests related to "
                + "Finance Specialist. If a request is unrelated, decline it briefly.",
                routerDTO.getSystemPrompt());
        assertEquals(5L, routerDTO.getModelRouteId());
        assertEquals(64, routerDTO.getMaxLoopCount());
        assertFalse(routerDTO.getAllowConsoleSession());
        assertFalse(routerDTO.getAllowWidgetSession());
        assertEquals("WORKER", workerDTO.getInstanceRole());
        assertEquals("You are a Finance Specialist. Use the available tools and skills to help "
                + "the user achieve their goals. Your job scope is Handle invoices and budgets."
                + "\n\nSTRICT: You may only reply to requests related to Finance Specialist. "
                + "If a request is unrelated, decline it briefly.", workerDTO.getSystemPrompt());
        assertEquals(5L, workerDTO.getModelRouteId());
        assertEquals(128, workerDTO.getMaxLoopCount());
        assertFalse(workerDTO.getAllowConsoleSession());
        assertFalse(workerDTO.getAllowWidgetSession());

        ArgumentCaptor<AgentSuperAgent> inserted = ArgumentCaptor.forClass(AgentSuperAgent.class);
        verify(superAgentMapper).insert(inserted.capture());
        assertEquals(11L, inserted.getValue().getRouterInstanceId());
        assertEquals(12L, inserted.getValue().getWorkerInstanceId());

        assertEquals(11L, result.getRouterInstanceId());
        assertEquals(12L, result.getWorkerInstanceId());
    }

    @Test
    void create_rejectsUnknownPosition() {
        when(systemDicSpi.findActive(SystemDicConstants.TYPE_POSITION, "NOPE")).thenReturn(null);
        CreateSuperAgentDTO dto = new CreateSuperAgentDTO();
        dto.setName("Support");
        dto.setPositionCode("NOPE");

        BizException error = assertThrows(BizException.class, () -> service.create(dto));

        assertEquals(CommonErrorCode.PARAM_INVALID.getCode(), error.getErrorCode());
        verify(superAgentMapper, times(0)).insert(any(AgentSuperAgent.class));
    }

    @Test
    void update_position_refreshesBothInstancePrompts() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(7L);
        agent.setOwnerUserId(1L);
        agent.setName("Support");
        agent.setPositionCode("FINANCE");
        agent.setPositionNameCn("财务专家");
        agent.setPositionNameEn("Finance Specialist");
        agent.setRouterInstanceId(11L);
        agent.setWorkerInstanceId(12L);
        when(superAgentMapper.selectById(7L)).thenReturn(agent);
        when(systemDicSpi.findActive(SystemDicConstants.TYPE_POSITION, "TECH"))
                .thenReturn(new SystemDicEntry("TECH", "技术专家", "Technology Specialist"));
        when(agentRouterPromptService.resolve()).thenReturn("global router prompt");
        InstanceVO router = instance(11L);
        router.setName("Support Router");
        InstanceVO worker = instance(12L);
        worker.setName("Support Worker");
        when(instanceSpi.getInstance(11L)).thenReturn(router);
        when(instanceSpi.getInstance(12L)).thenReturn(worker);

        CreateSuperAgentDTO dto = new CreateSuperAgentDTO();
        dto.setName("Support");
        dto.setPositionCode("TECH");
        dto.setDescription("Own payroll and tax");

        service.update(7L, dto);

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(instanceSpi, times(2)).updateSystemPrompt(anyLong(), prompts.capture());
        assertEquals("You are a Technology Specialist. Use the available tools and skills to help "
                + "the user achieve their goals. Your job scope is Own payroll and tax."
                + "\n\nglobal router prompt\n\nSTRICT: You may only reply to requests related to "
                + "Technology Specialist. If a request is unrelated, decline it briefly.",
                prompts.getAllValues().get(0));
        assertEquals("You are a Technology Specialist. Use the available tools and skills to help "
                + "the user achieve their goals. Your job scope is Own payroll and tax."
                + "\n\nSTRICT: You may only reply to requests related to Technology Specialist. "
                + "If a request is unrelated, decline it briefly.",
                prompts.getAllValues().get(1));
        assertEquals("TECH", agent.getPositionCode());
        assertEquals("技术专家", agent.getPositionNameCn());
    }

    @Test
    void updateModelRoute_updatesRouterAndWorker() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(7L);
        agent.setOwnerUserId(1L);
        agent.setRouterInstanceId(11L);
        agent.setWorkerInstanceId(12L);
        when(superAgentMapper.selectById(7L)).thenReturn(agent);

        service.updateModelRoute(7L, 33L);

        verify(instanceSpi).setModelRoute(11L, 33L);
        verify(instanceSpi).setModelRoute(12L, 33L);
    }

    @Test
    void instanceDeleted_archivesOwningSuperAgent() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(7L);
        agent.setOwnerUserId(1L);
        agent.setStatus(SuperAgentStatus.ACTIVE);
        agent.setRouterInstanceId(11L);
        agent.setWorkerInstanceId(12L);
        when(superAgentMapper.selectList(any())).thenReturn(List.of(agent));

        service.onInstanceDeleted(new InstanceDeletedEvent(11L));

        assertEquals(SuperAgentStatus.ARCHIVED, agent.getStatus());
        verify(superAgentMapper).updateById(agent);
    }

    @Test
    void getInstances_returnsRouterAndWorker() {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(7L);
        agent.setOwnerUserId(1L);
        agent.setRouterInstanceId(11L);
        agent.setWorkerInstanceId(12L);
        when(superAgentMapper.selectById(7L)).thenReturn(agent);
        InstanceVO router = instance(11L);
        InstanceVO worker = instance(12L);
        when(instanceSpi.getInstance(11L)).thenReturn(router);
        when(instanceSpi.getInstance(12L)).thenReturn(worker);

        var result = service.getInstances(7L);

        assertEquals(7L, result.getSuperAgentId());
        assertEquals(11L, result.getRouterInstance().getId());
        assertEquals(12L, result.getWorkerInstance().getId());
    }

    private static InstanceVO instance(Long id) {
        InstanceVO vo = new InstanceVO();
        vo.setId(id);
        return vo;
    }
}

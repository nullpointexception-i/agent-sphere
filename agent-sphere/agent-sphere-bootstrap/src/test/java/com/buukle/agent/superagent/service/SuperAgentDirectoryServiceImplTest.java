package com.buukle.agent.superagent.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.impl.SuperAgentDirectoryServiceImpl;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAgentDirectoryServiceImplTest {

    @Mock
    AgentSuperAgentMapper superAgentMapper;

    SuperAgentDirectoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SuperAgentDirectoryServiceImpl(superAgentMapper);
        AuthContext.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void resolveForChat_returnsOwnedAgent() {
        when(superAgentMapper.selectById(7L)).thenReturn(agent(1L));

        ChatAgentIdentity identity = service.resolveForChat(7L, 1L);

        assertEquals(7L, identity.agentId());
        assertEquals("Finance", identity.name());
        assertEquals(SuperAgentStatus.ACTIVE, identity.status());
    }

    @Test
    void resolveForChat_rejectsOtherUsersAgent() {
        when(superAgentMapper.selectById(7L)).thenReturn(agent(2L));

        BizException error = assertThrows(BizException.class,
                () -> service.resolveForChat(7L, 1L));

        assertEquals(CommonErrorCode.FORBIDDEN.getCode(), error.getErrorCode());
    }

    @Test
    void resolveForGroupChat_allowsPublicActiveAgent() {
        AgentSuperAgent agent = agent(2L);
        agent.setPublicVisible(true);
        when(superAgentMapper.selectById(7L)).thenReturn(agent);

        ChatAgentIdentity identity = service.resolveForGroupChat(7L, 1L);

        assertEquals(7L, identity.agentId());
    }

    @Test
    void resolveForGroupChat_rejectsPrivateOtherUsersAgent() {
        when(superAgentMapper.selectById(7L)).thenReturn(agent(2L));

        BizException error = assertThrows(BizException.class,
                () -> service.resolveForGroupChat(7L, 1L));

        assertEquals(CommonErrorCode.FORBIDDEN.getCode(), error.getErrorCode());
    }

    @Test
    void resolveForGroupChat_rejectsPausedPublicAgent() {
        AgentSuperAgent agent = agent(2L);
        agent.setPublicVisible(true);
        agent.setStatus(SuperAgentStatus.PAUSED);
        when(superAgentMapper.selectById(7L)).thenReturn(agent);

        BizException error = assertThrows(BizException.class,
                () -> service.resolveForGroupChat(7L, 1L));

        assertEquals(CommonErrorCode.PARAM_INVALID.getCode(), error.getErrorCode());
    }

    private static AgentSuperAgent agent(Long ownerUserId) {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(7L);
        agent.setName("Finance");
        agent.setOwnerUserId(ownerUserId);
        agent.setStatus(SuperAgentStatus.ACTIVE);
        return agent;
    }
}

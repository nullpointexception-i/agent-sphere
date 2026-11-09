package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.RouteSpi;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.spi.GroupChatBootstrapSpi.GroupChatBootstrapCommand;
import com.buukle.agent.superagent.spi.GroupChatBootstrapSpi.GroupChatBootstrapResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Thread/session bootstrap must happen at conversation creation, not on the first message. */
@ExtendWith(MockitoExtension.class)
class GroupChatBootstrapServiceImplTest {

    @Mock
    AgentThreadMapper threadMapper;
    @Mock
    InstanceSpi instanceSpi;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    RouteSpi routeSpi;

    @AfterEach
    void tearDown() {
        SessionSurfaceHolder.clear();
    }

    private GroupChatBootstrapServiceImpl service() {
        return new GroupChatBootstrapServiceImpl(threadMapper, instanceSpi, sessionSpi, routeSpi);
    }

    private GroupChatBootstrapCommand command(String type) {
        return new GroupChatBootstrapCommand(15L, type, "Group", "purpose", 27L, "bole_7", 3L);
    }

    @Test
    void dmCreatesThreadOnly() {
        given(threadMapper.selectOne(any())).willReturn(null);
        given(threadMapper.insert(any(AgentThread.class))).willAnswer(inv -> {
            AgentThread thread = inv.getArgument(0);
            thread.setId(101L);
            return 1;
        });

        GroupChatBootstrapResult result = service().bootstrap(command("DM"));

        assertEquals(101L, result.threadId());
        assertNull(result.groupRouterSessionId());
        verify(sessionSpi, never()).createSession(any());
    }

    @Test
    void groupCreatesThreadAndRouterSession() {
        given(threadMapper.selectOne(any())).willReturn(null);
        given(threadMapper.insert(any(AgentThread.class))).willAnswer(inv -> {
            AgentThread thread = inv.getArgument(0);
            thread.setId(101L);
            return 1;
        });
        ModelRouteVO systemRoute = new ModelRouteVO();
        systemRoute.setId(77L);
        given(routeSpi.findSystemRoute(3L)).willReturn(systemRoute);
        InstanceVO router = new InstanceVO();
        router.setId(9L);
        given(instanceSpi.findSystemByTenant(3L, "GROUP_ROUTER")).willReturn(router);
        given(sessionSpi.createSession(any())).willAnswer(inv -> {
            SessionVO session = new SessionVO();
            session.setId(201L);
            return session;
        });
        SessionSurfaceHolder.set(SessionSurface.CONSOLE);

        GroupChatBootstrapResult result = service().bootstrap(command("GROUP"));

        assertEquals(101L, result.threadId());
        assertEquals(201L, result.groupRouterSessionId());
        ArgumentCaptor<AgentThread> thread = ArgumentCaptor.forClass(AgentThread.class);
        verify(threadMapper).updateById(thread.capture());
        assertEquals(201L, thread.getValue().getGroupRouterSessionId());
    }

    @Test
    void groupWithoutTenantRouteFails() {
        given(threadMapper.selectOne(any())).willReturn(null);
        given(threadMapper.insert(any(AgentThread.class))).willAnswer(inv -> {
            AgentThread thread = inv.getArgument(0);
            thread.setId(101L);
            return 1;
        });
        given(routeSpi.findSystemRoute(3L)).willReturn(null);

        assertThrows(BizException.class, () -> service().bootstrap(command("GROUP")));
        verify(sessionSpi, never()).createSession(any());
    }
}

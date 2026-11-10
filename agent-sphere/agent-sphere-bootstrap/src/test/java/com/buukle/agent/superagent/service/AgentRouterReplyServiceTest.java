package com.buukle.agent.superagent.service;

import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.superagent.domain.AgentRouterFork;
import com.buukle.agent.superagent.repository.AgentRouterForkMapper;
import com.buukle.agent.superagent.service.impl.AgentRouterReplyService;
import com.buukle.agent.superagent.tool.AgentRouterToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentRouterReplyServiceTest {

    private static final Long CHILD_SESSION = 21L;
    private static final Long CHILD_RUN = 31L;
    private static final Long THREAD = 11L;

    @Mock
    AgentRouterToolService toolService;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    AgentRouterForkMapper forkMapper;

    AgentRouterReplyService service;

    @BeforeEach
    void setUp() {
        service = new AgentRouterReplyService(toolService, sessionSpi, forkMapper);
        ReflectionTestUtils.setField(service, "autoFinalEnabled", true);
    }

    @Test
    void forkReplyIsCapturedForSequenceMerge() {
        SessionVO child = new SessionVO();
        child.setId(CHILD_SESSION);
        child.setParentSessionId(20L);
        given(sessionSpi.getSession(CHILD_SESSION)).willReturn(child);
        AgentRouterFork fork = new AgentRouterFork();
        fork.setId(9L);
        fork.setState(AgentRouterFork.STATE_RUNNING);
        given(forkMapper.selectOne(any())).willReturn(fork);

        service.sendFinalReply(CHILD_SESSION, CHILD_RUN, THREAD, "fork answer");

        assertEquals(AgentRouterFork.STATE_COMPLETED, fork.getState());
        assertEquals("fork answer", fork.getReplyText());
        assertEquals(CHILD_RUN, fork.getChildRunId());
        verify(forkMapper).updateById(fork);
        verify(toolService, never()).sendFinalReply(any(), any(), any(), any());
    }

    @Test
    void parentReplyStillGoesToOutbound() {
        SessionVO parent = new SessionVO();
        parent.setId(CHILD_SESSION);
        given(sessionSpi.getSession(CHILD_SESSION)).willReturn(parent);

        service.sendFinalReply(CHILD_SESSION, CHILD_RUN, THREAD, "parent answer");

        verify(toolService).sendFinalReply(CHILD_SESSION, CHILD_RUN, THREAD, "parent answer");
        verify(forkMapper, never()).updateById(any(AgentRouterFork.class));
    }
}

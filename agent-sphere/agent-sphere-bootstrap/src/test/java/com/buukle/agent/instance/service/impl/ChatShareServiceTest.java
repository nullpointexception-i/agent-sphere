package com.buukle.agent.instance.service.impl;

import com.buukle.agent.instance.domain.AgentChatShare;
import com.buukle.agent.instance.domain.AgentRun;
import com.buukle.agent.instance.dtvo.vo.ChatShareVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.SharedChatVO;
import com.buukle.agent.instance.repository.AgentChatShareMapper;
import com.buukle.agent.instance.repository.RunMapper;
import com.buukle.agent.instance.service.ChatShareService;
import com.buukle.agent.instance.spi.SessionSpi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatShareServiceTest {

    @Mock AgentChatShareMapper shareMapper;
    @Mock RunMapper runMapper;
    @Mock SessionSpi sessionSpi;

    @Test
    void createStoresSelectedRunCount() {
        SessionVO session = new SessionVO();
        session.setId(1L);
        session.setCreatedBy("u");
        when(sessionSpi.getSession(1L)).thenReturn(session);
        when(runMapper.selectById(9L)).thenReturn(run(9L, "q", "a"));
        ChatShareService service = new ChatShareService(shareMapper, runMapper, sessionSpi);

        ChatShareVO vo = service.create(1L, 9L, 5, "u");

        assertNotNull(vo.getShareToken());
        ArgumentCaptor<AgentChatShare> captor = ArgumentCaptor.forClass(AgentChatShare.class);
        verify(shareMapper).insert(captor.capture());
        assertEquals(5, captor.getValue().getRunCount());
        assertEquals(9L, captor.getValue().getAnchorRunId());
    }

    @Test
    void sharedChatReturnsChronologicalRuns() {
        AgentChatShare share = new AgentChatShare();
        share.setShareToken("t");
        share.setSessionId(1L);
        share.setAnchorRunId(9L);
        share.setRunCount(2);
        when(shareMapper.selectOne(any())).thenReturn(share);
        AgentRun newer = run(9L, "q2", "a2");
        AgentRun older = run(8L, "q1", "a1");
        when(runMapper.selectList(any())).thenReturn(List.of(newer, older));
        SessionVO session = new SessionVO();
        session.setTitle("title");
        when(sessionSpi.getSession(1L)).thenReturn(session);
        ChatShareService service = new ChatShareService(shareMapper, runMapper, sessionSpi);

        SharedChatVO vo = service.getShared("t");

        assertEquals("title", vo.getTitle());
        assertEquals(2, vo.getRuns().size());
        assertEquals(8L, vo.getRuns().get(0).getRunId());
        assertEquals(9L, vo.getRuns().get(1).getRunId());
        assertFalse(vo.isHasMore());
    }

    private static AgentRun run(Long id, String user, String reply) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setSessionId(1L);
        run.setUserMessage(user);
        run.setAssistantReply(reply);
        return run;
    }
}

package com.buukle.agent.im.service.impl;

import com.buukle.agent.channel.spi.AgentStreamEvent;
import com.buukle.agent.channel.spi.AgentStreamPhase;
import com.buukle.agent.channel.spi.AgentStreamStatus;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.dtvo.enums.ImStreamEventType;
import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.service.ImStreamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImAgentStreamPublisherTest {

    @Mock
    ImConversationMapper conversationMapper;
    @Mock
    ImParticipantMapper participantMapper;
    @Mock
    ImStreamService streamService;

    @Test
    void publish_fansOutToConversationMembers() {
        ImAgentStreamPublisher publisher =
                new ImAgentStreamPublisher(conversationMapper, participantMapper, streamService);
        ImConversation conversation = new ImConversation();
        conversation.setId(44L);
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(44L, 10L), participant(44L, 21L)));

        publisher.publish(streamEvent("你好"));

        ArgumentCaptor<ImStreamEvent> captor = ArgumentCaptor.forClass(ImStreamEvent.class);
        verify(streamService).publish(org.mockito.ArgumentMatchers.eq(10L), captor.capture());
        verify(streamService).publish(org.mockito.ArgumentMatchers.eq(21L), any(ImStreamEvent.class));
        ImStreamEvent event = captor.getValue();
        assertEquals(ImStreamEventType.AGENT_STREAM, event.getType());
        assertEquals(44L, event.getConversationId());
        assertEquals(15L, event.getThreadId());
        assertEquals(7L, event.getRunId());
        assertEquals(AgentStreamPhase.REPLY, event.getPhase());
        assertEquals("你好", event.getDelta());
    }

    @Test
    void publish_ignoresUnknownThread() {
        ImAgentStreamPublisher publisher =
                new ImAgentStreamPublisher(conversationMapper, participantMapper, streamService);
        when(conversationMapper.selectOne(any())).thenReturn(null);

        publisher.publish(streamEvent("无会话"));

        verifyNoInteractions(streamService);
        verify(participantMapper, never()).selectList(any());
    }

    @Test
    void publish_ignoresNullInput() {
        ImAgentStreamPublisher publisher =
                new ImAgentStreamPublisher(conversationMapper, participantMapper, streamService);

        publisher.publish(null);
        publisher.publish(new AgentStreamEvent(null, null, null, null, null, null,
                null, null, null, null, null, null));

        verifyNoInteractions(conversationMapper, streamService);
    }

    private static ImParticipant participant(Long conversationId, Long userId) {
        ImParticipant member = new ImParticipant();
        member.setConversationId(conversationId);
        member.setParticipantType("USER");
        member.setParticipantId(userId);
        return member;
    }

    private static AgentStreamEvent streamEvent(String delta) {
        return new AgentStreamEvent(15L, 5L, 7L, 3L, AgentStreamPhase.REPLY, delta,
                AgentStreamStatus.STARTED, null, null, null, 1L, System.currentTimeMillis());
    }
}

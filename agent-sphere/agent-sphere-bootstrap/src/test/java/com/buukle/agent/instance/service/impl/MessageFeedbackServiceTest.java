package com.buukle.agent.instance.service.impl;

import com.buukle.agent.instance.domain.AgentMessageFeedback;
import com.buukle.agent.instance.repository.AgentMessageFeedbackMapper;
import com.buukle.agent.instance.service.MessageFeedbackService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageFeedbackServiceTest {

    @Mock AgentMessageFeedbackMapper mapper;

    @Test
    void likeInsertsFeedback() {
        MessageFeedbackService service = new MessageFeedbackService(mapper);
        when(mapper.selectOne(any())).thenReturn(null);

        assertEquals("LIKE", service.setFeedback(1L, 2L, 3L, 4L, "like"));

        ArgumentCaptor<AgentMessageFeedback> captor = ArgumentCaptor.forClass(AgentMessageFeedback.class);
        verify(mapper).insert(captor.capture());
        assertEquals("LIKE", captor.getValue().getValue());
        assertEquals(4L, captor.getValue().getUserId());
    }

    @Test
    void noneDeletesFeedback() {
        MessageFeedbackService service = new MessageFeedbackService(mapper);
        assertNull(service.setFeedback(1L, 2L, 3L, 4L, "NONE"));
        verify(mapper).delete(any());
        verify(mapper, never()).insert(any(AgentMessageFeedback.class));
    }

    @Test
    void getReturnsCurrentValue() {
        MessageFeedbackService service = new MessageFeedbackService(mapper);
        AgentMessageFeedback row = new AgentMessageFeedback();
        row.setValue("DISLIKE");
        when(mapper.selectOne(any())).thenReturn(row);
        assertEquals("DISLIKE", service.getFeedback(3L, 4L));
    }
}

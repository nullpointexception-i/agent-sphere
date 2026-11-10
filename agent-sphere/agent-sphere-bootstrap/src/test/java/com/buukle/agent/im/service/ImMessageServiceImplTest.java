package com.buukle.agent.im.service;

import com.buukle.agent.channel.spi.EventIngestPort;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImMessage;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.im.dtvo.dto.SendMessageDTO;
import com.buukle.agent.im.dtvo.enums.ImMessageStatus;
import com.buukle.agent.im.dtvo.enums.ImMessageType;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import com.buukle.agent.im.dtvo.vo.ImMessagePageVO;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.im.dtvo.vo.MessageVO;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImConversationSettingMapper;
import com.buukle.agent.im.repository.ImMessageMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.repository.ImReadCursorMapper;
import com.buukle.agent.im.service.impl.ImMessageServiceImpl;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.SessionInputSpi;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** IM sending: atomic sequence increment, idempotency, SSE notifications. */
@ExtendWith(MockitoExtension.class)
class ImMessageServiceImplTest {

    @Mock
    ImMessageMapper messageMapper;
    @Mock
    ImConversationMapper conversationMapper;
    @Mock
    ImConversationSettingMapper settingMapper;
    @Mock
    ImParticipantMapper participantMapper;
    @Mock
    ImReadCursorMapper readCursorMapper;
    @Mock
    ImStreamService streamService;
    @Mock
    ObjectProvider<EventIngestPort> ingestPortProvider;
    @Mock
    ObjectProvider<SuperAgentDirectorySpi> directoryPortProvider;
    @Mock
    ObjectProvider<SessionInputSpi> sessionInputPortProvider;
    @Mock
    ObjectProvider<UserSpi> userSpiProvider;
    @Mock
    SessionInputSpi sessionInputSpi;
    @Mock
    SuperAgentDirectorySpi directorySpi;
    @Mock
    AgentMetrics metrics;

    ImMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ImMessageServiceImpl(messageMapper, conversationMapper, settingMapper,
                participantMapper, readCursorMapper, streamService, ingestPortProvider,
                directoryPortProvider, sessionInputPortProvider, userSpiProvider, metrics);
        AuthContext.setUserId(1L);
        AuthContext.setUsername("alice");
        AuthContext.setDisplayName("Alice");
        lenient().when(ingestPortProvider.getIfAvailable()).thenReturn(null);
        lenient().when(directoryPortProvider.getIfAvailable()).thenReturn(null);
        lenient().when(sessionInputPortProvider.getIfAvailable()).thenReturn(sessionInputSpi);
        lenient().when(participantMapper.selectCount(any())).thenReturn(1L);
        lenient().when(readCursorMapper.selectOne(any())).thenReturn(null);
        lenient().when(conversationMapper.updateLastMessage(anyLong(), anyLong(), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void send_assignsNextSeq_andPublishesToMembers() {
        ImConversation conversation = conversation(10L, 4L);
        when(conversationMapper.selectById(10L)).thenReturn(conversation);
        when(conversationMapper.nextSeq(10L)).thenReturn(5L);
        when(messageMapper.insert(any(ImMessage.class))).thenAnswer(inv -> {
            ImMessage message = inv.getArgument(0);
            message.setId(100L);
            message.setCreatedAt(LocalDateTime.now());
            return 1;
        });
        ImParticipant member = new ImParticipant();
        member.setConversationId(10L);
        member.setParticipantType(ImParticipantType.USER);
        member.setParticipantId(1L);
        when(participantMapper.selectList(any())).thenReturn(List.of(member));

        SendMessageDTO dto = new SendMessageDTO();
        dto.setType(ImMessageType.TEXT);
        dto.setContent(Map.of("text", "hello"));

        MessageVO vo = service.send(10L, dto);

        assertNotNull(vo);
        assertEquals(5L, vo.getSeq());
        assertEquals(ImMessageStatus.SENT, vo.getStatus());
        verify(conversationMapper).nextSeq(10L);
        verify(conversationMapper).updateLastMessage(eq(10L), eq(100L), any());
        verify(streamService).publish(eq(1L), any(ImStreamEvent.class));
    }

    @Test
    void send_sameClientMsgId_returnsExistingWithoutNewSeq() {
        when(conversationMapper.selectById(10L)).thenReturn(conversation(10L, 4L));
        ImMessage existing = new ImMessage();
        existing.setId(99L);
        existing.setConversationId(10L);
        existing.setSeq(4L);
        existing.setSenderType(ImParticipantType.USER);
        existing.setSenderId(1L);
        existing.setClientMsgId("c-1");
        existing.setType(ImMessageType.TEXT);
        existing.setContent("{\"text\":\"dup\"}");
        existing.setStatus(ImMessageStatus.SENT);
        when(messageMapper.selectOne(any())).thenReturn(existing);

        SendMessageDTO dto = new SendMessageDTO();
        dto.setClientMsgId("c-1");
        dto.setContent(Map.of("text", "dup"));

        MessageVO vo = service.send(10L, dto);

        assertEquals(4L, vo.getSeq());
        verify(conversationMapper, never()).nextSeq(anyLong());
        verify(messageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    void sendAsAgent_writesAgentMessage_andPublishesToUsers() {
        when(conversationMapper.selectById(10L)).thenReturn(conversation(10L, 4L));
        when(conversationMapper.nextSeq(10L)).thenReturn(5L);
        when(messageMapper.insert(any(ImMessage.class))).thenAnswer(inv -> {
            ImMessage message = inv.getArgument(0);
            message.setId(101L);
            message.setCreatedAt(LocalDateTime.now());
            return 1;
        });
        ImParticipant member = new ImParticipant();
        member.setParticipantType(ImParticipantType.USER);
        member.setParticipantId(7L);
        when(participantMapper.selectList(any())).thenReturn(List.of(member));

        MessageVO vo = service.sendAsAgent(10L, 3L, "done", "agent-reply-1");

        assertNotNull(vo);
        assertEquals(5L, vo.getSeq());
        assertEquals(ImParticipantType.AGENT, vo.getSenderType());
        assertEquals(3L, vo.getSenderId());
        verify(streamService).publish(eq(7L), any(ImStreamEvent.class));
    }

    @Test
    void sendAsAgent_sameClientMsgId_returnsExistingWithoutNewSeq() {
        when(conversationMapper.selectById(10L)).thenReturn(conversation(10L, 4L));
        ImMessage existing = new ImMessage();
        existing.setId(99L);
        existing.setConversationId(10L);
        existing.setSeq(4L);
        existing.setSenderType(ImParticipantType.AGENT);
        existing.setSenderId(3L);
        existing.setClientMsgId("agent-reply-1");
        existing.setType(ImMessageType.TEXT);
        existing.setContent("{\"text\":\"done\"}");
        existing.setStatus(ImMessageStatus.SENT);
        when(messageMapper.selectOne(any())).thenReturn(existing);

        MessageVO vo = service.sendAsAgent(10L, 3L, "done", "agent-reply-1");

        assertEquals(4L, vo.getSeq());
        verify(conversationMapper, never()).nextSeq(anyLong());
        verify(messageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    void sendAsAgent_card_persistsCardTypeAndContent() {
        when(conversationMapper.selectById(10L)).thenReturn(conversation(10L, 4L));
        when(conversationMapper.nextSeq(10L)).thenReturn(6L);
        when(messageMapper.insert(any(ImMessage.class))).thenAnswer(inv -> {
            ImMessage message = inv.getArgument(0);
            message.setId(102L);
            message.setCreatedAt(LocalDateTime.now());
            return 1;
        });
        when(participantMapper.selectList(any())).thenReturn(List.of());

        MessageVO vo = service.sendAsAgent(10L, 3L, "CARD", Map.of("title", "task"), "card-1");

        assertEquals(ImMessageType.CARD, vo.getType());
        verify(conversationMapper).updateLastMessage(eq(10L), eq(102L), any());
    }

    @Test
    void page_reportsHasMoreAndOldestSeq() {
        ImMessage newest = message(3L, 3L, "newest");
        ImMessage middle = message(2L, 2L, "middle");
        ImMessage oldest = message(1L, 1L, "oldest");
        when(messageMapper.selectList(any())).thenReturn(List.of(newest, middle, oldest));

        ImMessagePageVO page = service.page(10L, null, null, 2);

        assertTrue(page.getHasMore());
        assertEquals(2, page.getRows().size());
        assertEquals(2L, page.getRows().get(0).getSeq());
        assertEquals(3L, page.getRows().get(1).getSeq());
        assertEquals(2L, page.getNextBeforeSeq());
    }

    @Test
    void search_returnsMatchesInAscendingSeqOrder() {
        ImMessage first = message(1L, 1L, "hello one");
        ImMessage second = message(2L, 2L, "hello two");
        when(messageMapper.search(eq(10L), eq("hello"), isNull(), eq(3)))
                .thenReturn(List.of(second, first));

        ImMessagePageVO page = service.search(10L, "hello", null, 2);

        assertFalse(page.getHasMore());
        assertEquals(1L, page.getRows().get(0).getSeq());
        assertEquals(2L, page.getRows().get(1).getSeq());
        assertEquals(1L, page.getNextBeforeSeq());
    }

    @Test
    void search_rejectsBlankKeyword() {
        assertThrows(com.buukle.agent.common.exception.BizException.class,
                () -> service.search(10L, " ", null, 30));
    }

    @Test
    void send_textIsNeverTreatedAsClarification() {
        ImConversation dm = conversation(10L, 4L);
        dm.setType("DM");
        dm.setThreadId(77L);
        when(conversationMapper.selectById(10L)).thenReturn(dm);
        when(conversationMapper.nextSeq(10L)).thenReturn(5L);
        when(messageMapper.insert(any(ImMessage.class))).thenAnswer(inv -> {
            ImMessage message = inv.getArgument(0);
            message.setId(104L);
            message.setCreatedAt(LocalDateTime.now());
            return 1;
        });
        when(participantMapper.selectList(any())).thenReturn(List.of());

        SendMessageDTO dto = new SendMessageDTO();
        dto.setContent(Map.of("text", "Regular chat"));
        service.send(10L, dto);

        verify(sessionInputSpi, never())
                .answerRouterClarification(any(), any(), any(), any());
        verify(ingestPortProvider).getIfAvailable();
    }

    @Test
    void send_groupConversation_textAlwaysUsesIngest() {
        ImConversation group = conversation(10L, 4L);
        group.setThreadId(77L);
        when(conversationMapper.selectById(10L)).thenReturn(group);
        when(conversationMapper.nextSeq(10L)).thenReturn(5L);
        when(messageMapper.insert(any(ImMessage.class))).thenAnswer(inv -> {
            ImMessage message = inv.getArgument(0);
            message.setId(105L);
            message.setCreatedAt(LocalDateTime.now());
            return 1;
        });
        when(participantMapper.selectList(any())).thenReturn(List.of());

        SendMessageDTO dto = new SendMessageDTO();
        dto.setContent(Map.of("text", "Option A"));
        service.send(10L, dto);

        verify(sessionInputSpi, never())
                .answerRouterClarification(any(), any(), any(), any());
        verify(ingestPortProvider).getIfAvailable();
    }

    private static ImMessage message(Long id, Long seq, String text) {
        ImMessage message = new ImMessage();
        message.setId(id);
        message.setConversationId(10L);
        message.setSeq(seq);
        message.setSenderType(ImParticipantType.USER);
        message.setSenderId(1L);
        message.setType(ImMessageType.TEXT);
        message.setContent("{\"text\":\"" + text + "\"}");
        return message;
    }

    private static ImConversation conversation(Long id, Long lastSeq) {
        ImConversation conversation = new ImConversation();
        conversation.setId(id);
        conversation.setLastSeq(lastSeq);
        conversation.setType("GROUP");
        return conversation;
    }
}

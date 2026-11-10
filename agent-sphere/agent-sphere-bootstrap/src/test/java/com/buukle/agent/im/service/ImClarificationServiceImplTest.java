package com.buukle.agent.im.service;

import com.buukle.agent.channel.spi.TaskCardPayload;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImMessage;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.dtvo.dto.AnswerClarificationDTO;
import com.buukle.agent.im.dtvo.enums.ImMessageType;
import com.buukle.agent.im.dtvo.vo.ClarificationAnswerVO;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImMessageMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.service.impl.ImClarificationServiceImpl;
import com.buukle.agent.superagent.dtvo.vo.SessionInputVO;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.SessionInputSpi;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import com.buukle.agent.util.json.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Card-only clarification answering: validation, permissions and card resolution. */
@ExtendWith(MockitoExtension.class)
class ImClarificationServiceImplTest {

    private static final Long CONVERSATION_ID = 10L;
    private static final Long THREAD_ID = 77L;
    private static final String INPUT_ID = "c-1";
    private static final String OPEN_GATE = "{\"trigger\":\"MENTION\"}";
    private static final String STRICT_GATE =
            "{\"trigger\":\"MENTION\",\"clarificationStrict\":true}";

    @Mock
    ImConversationMapper conversationMapper;
    @Mock
    ImMessageMapper messageMapper;
    @Mock
    ImParticipantMapper participantMapper;
    @Mock
    ImStreamService streamService;
    @Mock
    ObjectProvider<SessionInputSpi> sessionInputPortProvider;
    @Mock
    ObjectProvider<SuperAgentDirectorySpi> directoryPortProvider;
    @Mock
    SessionInputSpi sessionInputSpi;
    @Mock
    SuperAgentDirectorySpi directorySpi;

    ImClarificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ImClarificationServiceImpl(conversationMapper, messageMapper,
                participantMapper, streamService, sessionInputPortProvider, directoryPortProvider);
        AuthContext.setUserId(1L);
        AuthContext.setUsername("alice");
        AuthContext.setDisplayName("Alice");
        lenient().when(sessionInputPortProvider.getIfAvailable()).thenReturn(sessionInputSpi);
        lenient().when(directoryPortProvider.getIfAvailable()).thenReturn(directorySpi);
        lenient().when(participantMapper.selectCount(any())).thenReturn(1L);
        lenient().when(participantMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void answer_freeText_resumesRunAndResolvesCard() {
        stubConversation(OPEN_GATE);
        SessionInputVO input = pendingInput();
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(input);
        when(sessionInputSpi.answerRouterClarification(THREAD_ID, INPUT_ID, "quarterly", "alice"))
                .thenReturn(true);
        ImMessage card = cardMessage();
        when(messageMapper.selectList(any())).thenReturn(List.of(card));

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setMode(AnswerClarificationDTO.MODE_FREE_TEXT);
        dto.setText("  quarterly ");

        ClarificationAnswerVO vo = service.answer(CONVERSATION_ID, INPUT_ID, dto);

        assertEquals("ANSWERED", vo.getStatus());
        assertEquals("quarterly", vo.getAnswer());
        assertEquals("alice", vo.getRespondedBy());
        assertNotNull(vo.getRespondedAt());

        ArgumentCaptor<ImMessage> updated = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).updateById(updated.capture());
        TaskCardPayload resolved = JsonUtils.parse(updated.getValue().getContent(),
                TaskCardPayload.class);
        assertEquals("ANSWERED", resolved.getStatus());
        assertEquals("alice", resolved.getRespondedBy());
        assertTrue(resolved.getDecisionsJson().contains("\"quarterly\""));
    }

    @Test
    void answer_singleOption_requiresOneKnownOption() {
        stubConversation(OPEN_GATE);
        SessionInputVO input = pendingInput();
        input.setOptions("[\"A\",\"B\"]");
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(input);

        AnswerClarificationDTO unknown = new AnswerClarificationDTO();
        unknown.setMode(AnswerClarificationDTO.MODE_SINGLE);
        unknown.setSelectedOptions(List.of("C"));
        assertThrows(BizException.class, () -> service.answer(CONVERSATION_ID, INPUT_ID, unknown));

        AnswerClarificationDTO two = new AnswerClarificationDTO();
        two.setMode(AnswerClarificationDTO.MODE_SINGLE);
        two.setSelectedOptions(List.of("A", "B"));
        assertThrows(BizException.class, () -> service.answer(CONVERSATION_ID, INPUT_ID, two));

        verify(sessionInputSpi, never())
                .answerRouterClarification(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void answer_multiOption_joinsSelectedOptions() {
        stubConversation(OPEN_GATE);
        SessionInputVO input = pendingInput();
        input.setOptions("[\"A\",\"B\"]");
        input.setMultiple(true);
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(input);
        when(sessionInputSpi.answerRouterClarification(THREAD_ID, INPUT_ID, "A, B", "alice"))
                .thenReturn(true);
        when(messageMapper.selectList(any())).thenReturn(List.of(cardMessage()));

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setMode(AnswerClarificationDTO.MODE_MULTI);
        dto.setSelectedOptions(List.of("A", "B"));

        ClarificationAnswerVO vo = service.answer(CONVERSATION_ID, INPUT_ID, dto);

        assertEquals("A, B", vo.getAnswer());
        assertEquals(List.of("A", "B"), vo.getSelectedOptions());
    }

    @Test
    void answer_freeTextWhenOptionsExist_isRejected() {
        stubConversation(OPEN_GATE);
        SessionInputVO input = pendingInput();
        input.setOptions("[\"A\"]");
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(input);

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setText("typed answer");

        assertThrows(BizException.class, () -> service.answer(CONVERSATION_ID, INPUT_ID, dto));
    }

    @Test
    void answer_strictGroup_allowsAsker() {
        stubConversation(STRICT_GATE);
        SessionInputVO input = pendingInput();
        input.setRequestedBy(1L);
        input.setSuperAgentId(9L);
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(input);
        when(sessionInputSpi.answerRouterClarification(THREAD_ID, INPUT_ID, "yes", "alice"))
                .thenReturn(true);
        when(messageMapper.selectList(any())).thenReturn(List.of(cardMessage()));

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setMode(AnswerClarificationDTO.MODE_FREE_TEXT);
        dto.setText("yes");

        service.answer(CONVERSATION_ID, INPUT_ID, dto);

        verify(sessionInputSpi).answerRouterClarification(THREAD_ID, INPUT_ID, "yes", "alice");
    }

    @Test
    void answer_strictGroup_allowsAvatarOwner() {
        stubConversation(STRICT_GATE);
        SessionInputVO input = pendingInput();
        input.setRequestedBy(2L);
        input.setSuperAgentId(9L);
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(input);
        when(directorySpi.findByIds(List.of(9L))).thenReturn(Map.of(
                9L, new ChatAgentIdentity(9L, "Finance", 1L, "ACTIVE")));
        when(sessionInputSpi.answerRouterClarification(THREAD_ID, INPUT_ID, "yes", "alice"))
                .thenReturn(true);
        when(messageMapper.selectList(any())).thenReturn(List.of(cardMessage()));

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setMode(AnswerClarificationDTO.MODE_FREE_TEXT);
        dto.setText("yes");

        service.answer(CONVERSATION_ID, INPUT_ID, dto);

        verify(sessionInputSpi).answerRouterClarification(THREAD_ID, INPUT_ID, "yes", "alice");
    }

    @Test
    void answer_strictGroup_rejectsPlainMember() {
        stubConversation(STRICT_GATE);
        SessionInputVO input = pendingInput();
        input.setRequestedBy(2L);
        input.setSuperAgentId(9L);
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(input);
        when(directorySpi.findByIds(List.of(9L))).thenReturn(Map.of(
                9L, new ChatAgentIdentity(9L, "Finance", 3L, "ACTIVE")));
        ImParticipant member = new ImParticipant();
        member.setRole("MEMBER");
        when(participantMapper.selectOne(any())).thenReturn(member);

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setMode(AnswerClarificationDTO.MODE_FREE_TEXT);
        dto.setText("yes");

        assertThrows(BizException.class, () -> service.answer(CONVERSATION_ID, INPUT_ID, dto));
        verify(sessionInputSpi, never())
                .answerRouterClarification(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void answer_nonMember_isForbidden() {
        stubConversation(OPEN_GATE);
        when(participantMapper.selectCount(any())).thenReturn(0L);

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setMode(AnswerClarificationDTO.MODE_FREE_TEXT);
        dto.setText("yes");

        assertThrows(BizException.class, () -> service.answer(CONVERSATION_ID, INPUT_ID, dto));
    }

    @Test
    void answer_alreadyResolved_isRejected() {
        stubConversation(OPEN_GATE);
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(null);

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setMode(AnswerClarificationDTO.MODE_FREE_TEXT);
        dto.setText("yes");

        assertThrows(BizException.class, () -> service.answer(CONVERSATION_ID, INPUT_ID, dto));
        verify(messageMapper, never()).updateById(any(ImMessage.class));
    }

    @Test
    void answer_resumeFailure_keepsCardPending() {
        stubConversation(OPEN_GATE);
        SessionInputVO input = pendingInput();
        when(sessionInputSpi.findPendingByInput(THREAD_ID, INPUT_ID)).thenReturn(input);
        when(sessionInputSpi.answerRouterClarification(THREAD_ID, INPUT_ID, "yes", "alice"))
                .thenReturn(false);

        AnswerClarificationDTO dto = new AnswerClarificationDTO();
        dto.setMode(AnswerClarificationDTO.MODE_FREE_TEXT);
        dto.setText("yes");

        assertThrows(BizException.class, () -> service.answer(CONVERSATION_ID, INPUT_ID, dto));
        verify(messageMapper, never()).updateById(any(ImMessage.class));
    }

    private void stubConversation(String gateConfig) {
        ImConversation conversation = new ImConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setThreadId(THREAD_ID);
        conversation.setType("GROUP");
        conversation.setGateConfig(gateConfig);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);
    }

    private SessionInputVO pendingInput() {
        SessionInputVO input = new SessionInputVO();
        input.setInputId(INPUT_ID);
        input.setThreadId(THREAD_ID);
        input.setStatus("PENDING");
        input.setInputType("CLARIFICATION");
        return input;
    }

    private ImMessage cardMessage() {
        TaskCardPayload card = new TaskCardPayload();
        card.setCardType("ROUTER_CLARIFICATION");
        card.setInputId(INPUT_ID);
        card.setStatus("PENDING");
        ImMessage message = new ImMessage();
        message.setId(500L);
        message.setConversationId(CONVERSATION_ID);
        message.setType(ImMessageType.CARD);
        message.setContent(JsonUtils.toJson(card));
        return message;
    }
}

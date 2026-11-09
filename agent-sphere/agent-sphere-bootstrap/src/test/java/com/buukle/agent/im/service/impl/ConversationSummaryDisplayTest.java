package com.buukle.agent.im.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.dtvo.enums.ImConversationType;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.dtvo.vo.ConversationPageVO;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImConversationSettingMapper;
import com.buukle.agent.im.repository.ImMessageMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.repository.ImReadCursorMapper;
import com.buukle.agent.im.service.ImStreamService;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.sso.spi.SsoUserLabelVO;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

/** Conversation list rows (page summary) must carry the SSO label, not the raw username. */
@ExtendWith(MockitoExtension.class)
class ConversationSummaryDisplayTest {

    private static final Long SELF_ID = 27L;
    private static final Long OTHER_ID = 31L;

    @Mock
    ImConversationMapper conversationMapper;
    @Mock
    ImParticipantMapper participantMapper;
    @Mock
    ImMessageMapper messageMapper;
    @Mock
    ImReadCursorMapper readCursorMapper;
    @Mock
    ImConversationSettingMapper settingMapper;
    @Mock
    UserSpi userSpi;
    @Mock
    SsoIdentitySpi ssoIdentitySpi;
    @Mock
    ImStreamService streamService;
    @Mock
    ObjectProvider<SuperAgentDirectorySpi> directoryPortProvider;
    @Mock
    ObjectProvider<com.buukle.agent.superagent.spi.GroupChatBootstrapSpi> groupChatBootstrapProvider;

    ImConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ImConversationServiceImpl(conversationMapper, participantMapper,
                messageMapper, readCursorMapper, settingMapper, userSpi, ssoIdentitySpi,
                streamService, directoryPortProvider, groupChatBootstrapProvider);
        AuthContext.setUserId(SELF_ID);
        AuthContext.setUsername("bole_7");
        AuthContext.setDisplayName("elvin");
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void pageSummaryUsesSsoLabelForCounterpart() {
        ImConversation conversation = new ImConversation();
        conversation.setId(15L);
        conversation.setType(ImConversationType.DM);
        conversation.setTitle("bole_8");
        given(conversationMapper.pageForUser(any(), any(), any(), any(), any(), anyInt()))
                .willReturn(List.of(conversation));
        given(participantMapper.selectList(any())).willReturn(List.of(
                user(SELF_ID, "bole_7"), user(OTHER_ID, "bole_8")));
        given(readCursorMapper.selectList(any())).willReturn(List.of());
        UserIdentityVO other = new UserIdentityVO();
        other.setId(OTHER_ID);
        other.setUsername("bole_8");
        other.setDisplayName("bole_8");
        given(userSpi.identityMap(any())).willReturn(Map.of(OTHER_ID, other));
        given(ssoIdentitySpi.labelsByUserIds(any())).willReturn(Map.of(
                OTHER_ID, new SsoUserLabelVO(OTHER_ID, "bole", "Celine")));

        ConversationPageVO page = service.page(20, null, null);

        assertEquals(1, page.getRows().size());
        assertEquals("bole@Celine", page.getRows().get(0).getDisplayName());
        assertEquals("bole_8", page.getRows().get(0).getTitle());
    }

    private static ImParticipant user(Long id, String displayName) {
        ImParticipant participant = new ImParticipant();
        participant.setConversationId(15L);
        participant.setParticipantType(ImParticipantType.USER);
        participant.setParticipantId(id);
        participant.setDisplayName(displayName);
        return participant;
    }
}

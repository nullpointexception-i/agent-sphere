package com.buukle.agent.im.service.impl;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImConversationSetting;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.domain.ImReadCursor;
import com.buukle.agent.im.dtvo.enums.ImConversationStatus;
import com.buukle.agent.im.dtvo.enums.ImConversationType;
import com.buukle.agent.im.dtvo.enums.ImParticipantRole;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.dtvo.enums.ImStreamEventType;
import com.buukle.agent.im.dtvo.vo.ImStreamEvent;
import com.buukle.agent.im.repository.ImConversationMapper;
import com.buukle.agent.im.repository.ImConversationSettingMapper;
import com.buukle.agent.im.repository.ImMessageMapper;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.repository.ImReadCursorMapper;
import com.buukle.agent.im.service.ImStreamService;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.EntityAccessGuard;
import com.buukle.agent.superagent.spi.EntityAgentDirectorySpi;
import com.buukle.agent.superagent.spi.GroupChatBootstrapSpi;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Deleting a chat only hides it for the current user. Starting the same avatar chat again
 * must bring the existing DM back into the list instead of opening an invisible thread.
 */
@ExtendWith(MockitoExtension.class)
class ConversationRevealTest {

    private static final Long USER_ID = 1L;
    private static final Long AGENT_ID = 3L;
    private static final Long CONVERSATION_ID = 9L;

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
    ObjectProvider<SuperAgentDirectorySpi> directoryProvider;
    @Mock
    ObjectProvider<GroupChatBootstrapSpi> bootstrapProvider;
    @Mock
    ObjectProvider<EntityAccessGuard> entityAccessGuardProvider;
    @Mock
    ObjectProvider<EntityAgentDirectorySpi> entityAgentDirectoryProvider;
    @Mock
    SuperAgentDirectorySpi directory;

    private ImConversationServiceImpl service;

    @BeforeAll
    static void initMpTableInfo() {
        // Unit tests have no MyBatis-Plus context: LambdaWrapper needs the entity TableInfo.
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : new Class<?>[] {
                ImConversation.class, ImConversationSetting.class,
                ImParticipant.class, ImReadCursor.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        }
    }

    @BeforeEach
    void setUp() {
        service = new ImConversationServiceImpl(
                conversationMapper, participantMapper, messageMapper, readCursorMapper,
                settingMapper, userSpi, ssoIdentitySpi, streamService,
                directoryProvider, bootstrapProvider, entityAccessGuardProvider,
                entityAgentDirectoryProvider);
        AuthContext.setUserId(USER_ID);
        AuthContext.setUsername("alice");
        given(directoryProvider.getIfAvailable()).willReturn(directory);
        given(directory.resolveForChat(AGENT_ID, USER_ID))
                .willReturn(new ChatAgentIdentity(AGENT_ID, "Assistant", USER_ID, SuperAgentStatus.ACTIVE));
        ImParticipant self = participant(USER_ID, ImParticipantType.USER, ImParticipantRole.OWNER);
        List<ImParticipant> members = List.of(self,
                participant(AGENT_ID, ImParticipantType.AGENT, ImParticipantRole.BOT));
        given(participantMapper.selectList(any()))
                .willReturn(List.of(self), members, members);
        given(conversationMapper.selectList(any()))
                .willReturn(List.of(conversation()));
        given(readCursorMapper.selectOne(any())).willReturn(null);
        given(userSpi.identityMap(anyList())).willReturn(Map.of());
        given(directory.ownerUserIds(anyList())).willReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantScopeHolder.clear();
        TenantContext.clear();
    }

    @Test
    void startingAgentChatRevealsHiddenConversation() {
        ImConversationSetting setting = setting(true);
        given(settingMapper.selectOne(any())).willReturn(setting);

        service.createAgentDm(AGENT_ID);

        assertFalse(setting.getHidden());
        verify(settingMapper).updateById(setting);
        ArgumentCaptor<ImStreamEvent> event = ArgumentCaptor.forClass(ImStreamEvent.class);
        verify(streamService).publish(eq(USER_ID), event.capture());
        assertEquals(ImStreamEventType.CONVERSATION_CREATED, event.getValue().getType());
        assertEquals(CONVERSATION_ID, event.getValue().getConversationId());
    }

    @Test
    void startingAgentChatKeepsVisibleConversationUntouched() {
        ImConversationSetting setting = setting(false);
        given(settingMapper.selectOne(any())).willReturn(setting);

        service.createAgentDm(AGENT_ID);

        verify(settingMapper, never()).updateById(any(ImConversationSetting.class));
        verify(streamService, never()).publish(any(), any());
    }

    private static ImConversation conversation() {
        ImConversation conversation = new ImConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setType(ImConversationType.DM);
        conversation.setTitle("Assistant");
        conversation.setOwnerUserId(USER_ID);
        conversation.setStatus(ImConversationStatus.ACTIVE);
        conversation.setLastSeq(0L);
        return conversation;
    }

    private static ImParticipant participant(Long participantId, String type, String role) {
        ImParticipant participant = new ImParticipant();
        participant.setConversationId(CONVERSATION_ID);
        participant.setParticipantId(participantId);
        participant.setParticipantType(type);
        participant.setRole(role);
        participant.setDisplayName("member-" + participantId);
        participant.setMuted(false);
        participant.setAutoAdded(false);
        participant.setJoinedAt(LocalDateTime.now());
        return participant;
    }

    private static ImConversationSetting setting(boolean hidden) {
        ImConversationSetting setting = new ImConversationSetting();
        setting.setId(77L);
        setting.setConversationId(CONVERSATION_ID);
        setting.setUserId(USER_ID);
        setting.setPinned(false);
        setting.setMuted(false);
        setting.setHidden(hidden);
        setting.setStatus(ImConversationStatus.ACTIVE);
        return setting;
    }
}

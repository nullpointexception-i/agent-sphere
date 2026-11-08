package com.buukle.agent.im.service.impl;

import com.buukle.agent.im.domain.ImConversation;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.dtvo.enums.ImConversationType;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationDisplayTest {

    private static final Long SELF_ID = 1L;
    private static final Long OTHER_ID = 2L;

    @Test
    void dmShowsOtherUser() {
        ImConversation conversation = conversation(ImConversationType.DM, "Bob");
        List<ImParticipant> members = List.of(
                user(SELF_ID, "Alice"), user(OTHER_ID, null));

        ImConversationServiceImpl.ConversationDisplay display =
                ImConversationServiceImpl.resolveDisplay(conversation, SELF_ID, members,
                        Map.of(OTHER_ID, identity(OTHER_ID, "bob", "Bob", "avatar-b")),
                        "Alice", "alice");

        assertEquals("Bob", display.displayName());
        assertEquals("avatar-b", display.avatar());
        assertFalse(display.selfConversation());
    }

    @Test
    void dmWithOnlySelfIsSelfConversation() {
        ImConversation conversation = conversation(ImConversationType.DM, "Myself");

        ImConversationServiceImpl.ConversationDisplay display =
                ImConversationServiceImpl.resolveDisplay(conversation, SELF_ID,
                        List.of(user(SELF_ID, "Alice")), Map.of(), "Alice", "alice");

        assertNull(display.displayName());
        assertTrue(display.selfConversation());
    }

    @Test
    void agentDmShowsAgentName() {
        ImConversation conversation = conversation(ImConversationType.DM, "Assistant");
        List<ImParticipant> members = List.of(
                user(SELF_ID, "Alice"), agent(3L, "Assistant"));

        ImConversationServiceImpl.ConversationDisplay display =
                ImConversationServiceImpl.resolveDisplay(conversation, SELF_ID, members,
                        Map.of(), "Alice", "alice");

        assertEquals("Assistant", display.displayName());
        assertFalse(display.selfConversation());
    }

    @Test
    void twoPersonGroupNamedAfterSelfShowsOtherUser() {
        ImConversation conversation = conversation(ImConversationType.GROUP, "Alice");
        List<ImParticipant> members = List.of(
                user(SELF_ID, null), user(OTHER_ID, null));

        ImConversationServiceImpl.ConversationDisplay display =
                ImConversationServiceImpl.resolveDisplay(conversation, SELF_ID, members,
                        Map.of(OTHER_ID, identity(OTHER_ID, "bob", "Bob", "avatar-b")),
                        "Alice", "alice");

        assertEquals("Bob", display.displayName());
        assertEquals("avatar-b", display.avatar());
    }

    @Test
    void twoPersonGroupNamedByUsernameMatchesSelf() {
        ImConversation conversation = conversation(ImConversationType.GROUP, "alice");
        List<ImParticipant> members = List.of(
                user(SELF_ID, null), user(OTHER_ID, null));

        ImConversationServiceImpl.ConversationDisplay display =
                ImConversationServiceImpl.resolveDisplay(conversation, SELF_ID, members,
                        Map.of(OTHER_ID, identity(OTHER_ID, "bob", "Bob", null)),
                        "Alice", "alice");

        assertEquals("Bob", display.displayName());
    }

    @Test
    void twoPersonGroupWithCustomTitleKeepsTitle() {
        ImConversation conversation = conversation(ImConversationType.GROUP, "Project X");
        List<ImParticipant> members = List.of(
                user(SELF_ID, null), user(OTHER_ID, null));

        ImConversationServiceImpl.ConversationDisplay display =
                ImConversationServiceImpl.resolveDisplay(conversation, SELF_ID, members,
                        Map.of(OTHER_ID, identity(OTHER_ID, "bob", "Bob", null)),
                        "Alice", "alice");

        assertNull(display.displayName());
    }

    @Test
    void largerGroupKeepsTitle() {
        ImConversation conversation = conversation(ImConversationType.GROUP, "Alice");
        List<ImParticipant> members = List.of(
                user(SELF_ID, null), user(OTHER_ID, null), user(3L, null));

        ImConversationServiceImpl.ConversationDisplay display =
                ImConversationServiceImpl.resolveDisplay(conversation, SELF_ID, members,
                        Map.of(OTHER_ID, identity(OTHER_ID, "bob", "Bob", null)),
                        "Alice", "alice");

        assertNull(display.displayName());
    }

    @Test
    void twoPersonGroupWithBlankTitleShowsOtherUser() {
        ImConversation conversation = conversation(ImConversationType.GROUP, null);
        List<ImParticipant> members = List.of(
                user(SELF_ID, null), user(OTHER_ID, null));

        ImConversationServiceImpl.ConversationDisplay display =
                ImConversationServiceImpl.resolveDisplay(conversation, SELF_ID, members,
                        Map.of(OTHER_ID, identity(OTHER_ID, "bob", "Bob", null)),
                        "Alice", "alice");

        assertEquals("Bob", display.displayName());
    }

    private static ImConversation conversation(String type, String title) {
        ImConversation conversation = new ImConversation();
        conversation.setType(type);
        conversation.setTitle(title);
        return conversation;
    }

    private static ImParticipant user(Long id, String displayName) {
        return participant(ImParticipantType.USER, id, displayName);
    }

    private static ImParticipant agent(Long id, String displayName) {
        return participant(ImParticipantType.AGENT, id, displayName);
    }

    private static ImParticipant participant(String type, Long id, String displayName) {
        ImParticipant participant = new ImParticipant();
        participant.setParticipantType(type);
        participant.setParticipantId(id);
        participant.setDisplayName(displayName);
        return participant;
    }

    private static UserIdentityVO identity(Long id, String username, String displayName, String avatar) {
        UserIdentityVO identity = new UserIdentityVO();
        identity.setId(id);
        identity.setUsername(username);
        identity.setDisplayName(displayName);
        identity.setAvatar(avatar);
        return identity;
    }
}

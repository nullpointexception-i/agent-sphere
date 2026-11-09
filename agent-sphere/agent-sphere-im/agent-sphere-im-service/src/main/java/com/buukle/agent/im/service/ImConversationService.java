package com.buukle.agent.im.service;

import com.buukle.agent.im.dtvo.dto.AddParticipantDTO;
import com.buukle.agent.im.dtvo.dto.CreateConversationDTO;
import com.buukle.agent.im.dtvo.dto.ImGateConfig;
import com.buukle.agent.im.dtvo.dto.MarkReadDTO;
import com.buukle.agent.im.dtvo.dto.UpdateConversationSettingDTO;
import com.buukle.agent.im.dtvo.vo.ConversationPageVO;
import com.buukle.agent.im.dtvo.vo.ConversationSettingVO;
import com.buukle.agent.im.dtvo.vo.ConversationVO;
import com.buukle.agent.im.dtvo.vo.ParticipantVO;

import java.util.List;

public interface ImConversationService {

    ConversationVO create(CreateConversationDTO dto);

    /** Create or reuse a DM between the current user and one Super-Agent. */
    ConversationVO createAgentDm(Long agentId);

    List<ConversationVO> list();

    ConversationPageVO page(int limit, String cursor, String type);

    ConversationVO get(Long conversationId);

    ConversationVO updateMeta(Long conversationId, String title, String purpose);

    List<ParticipantVO> participants(Long conversationId);

    ParticipantVO addParticipant(Long conversationId, AddParticipantDTO dto);

    void removeParticipant(Long conversationId, Long participantId);

    ParticipantVO updateParticipantRole(Long conversationId, Long participantId, String role);

    ConversationVO transferOwner(Long conversationId, Long newOwnerUserId);

    ImGateConfig updateInvitePolicy(Long conversationId, boolean allowMemberInvite);

    void updateParticipantMute(Long conversationId, Long participantId, boolean muted);

    void markRead(Long conversationId, MarkReadDTO dto);

    ImGateConfig getGateConfig(Long conversationId);

    ImGateConfig updateGateConfig(Long conversationId, ImGateConfig config);

    ConversationSettingVO getSetting(Long conversationId);

    ConversationSettingVO updateSetting(Long conversationId, UpdateConversationSettingDTO dto);

    void hide(Long conversationId);

    ConversationVO pinMessage(Long conversationId, Long messageId);

    ConversationVO unpinMessage(Long conversationId);

    boolean isMember(Long conversationId, Long userId);
}

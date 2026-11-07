package com.buukle.agent.im.service;

import com.buukle.agent.im.dtvo.dto.SendMessageDTO;
import com.buukle.agent.im.dtvo.vo.ImMessagePageVO;
import com.buukle.agent.im.dtvo.vo.MessageVO;

import java.util.List;

public interface ImMessageService {

    MessageVO send(Long conversationId, SendMessageDTO dto);

    /**
     * Agent outbound: bypasses user membership/ownership checks, writes to the conversation as
     * AGENT, and does not trigger inbound events.
     */
    MessageVO sendAsAgent(Long conversationId, Long superAgentId, String text, String clientMsgId);

    /**
     * Agent outbound (explicit message type, e.g. CARD): content is serialized directly into
     * im_message.content.
     */
    MessageVO sendAsAgent(Long conversationId, Long superAgentId, String type,
                          Object content, String clientMsgId);

    /**
     * Agent updates the content of an already-sent message (task card status changes); only
     * AGENT-sent messages may be updated.
     */
    MessageVO updateAsAgent(Long conversationId, Long messageId, String type, Object content);

    List<MessageVO> list(Long conversationId, Long beforeSeq, Long afterSeq, int limit);

    /** Keyset history page with {@code hasMore} so the client knows when to stop. */
    ImMessagePageVO page(Long conversationId, Long beforeSeq, Long afterSeq, int limit);

    /** Text search across the conversation, newest matches first. */
    ImMessagePageVO search(Long conversationId, String keyword, Long beforeSeq, int limit);

    MessageVO edit(Long conversationId, Long messageId, Object content);

    void recall(Long conversationId, Long messageId);
}

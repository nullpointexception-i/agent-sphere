package com.buukle.agent.im.service;

import com.buukle.agent.im.dtvo.dto.SendMessageDTO;
import com.buukle.agent.im.dtvo.vo.ImMessagePageVO;
import com.buukle.agent.im.dtvo.vo.MessageVO;

import java.util.List;

public interface ImMessageService {

    MessageVO send(Long conversationId, SendMessageDTO dto);

    /**
     * Agent 出站：绕过用户成员/归属校验，以 AGENT 身份写入会话，不再触发入站事件。
     */
    MessageVO sendAsAgent(Long conversationId, Long superAgentId, String text, String clientMsgId);

    /**
     * Agent 出站（指定消息类型，如 CARD）：content 直接序列化进 im_message.content。
     */
    MessageVO sendAsAgent(Long conversationId, Long superAgentId, String type,
                          Object content, String clientMsgId);

    /**
     * Agent 更新已发送的消息内容（任务卡片状态变更）；只允许更新 AGENT 发出的消息。
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

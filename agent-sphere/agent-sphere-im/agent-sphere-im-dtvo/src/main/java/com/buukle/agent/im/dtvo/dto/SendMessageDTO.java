package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SendMessageDTO implements Serializable {

    /** 客户端幂等键；同会话内唯一。 */
    @Size(max = 64)
    private String clientMsgId;
    /** TEXT / MARKDOWN / CARD / FILE / IMAGE / SYSTEM。 */
    private String type;
    /** 正文/卡片/附件内容；持久化为 JSON。 */
    private Object content;
    private Long replyToId;
    private List<MentionDTO> mentions;
}

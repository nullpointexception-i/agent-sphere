package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SendMessageDTO implements Serializable {

    /** Client idempotency key; unique within a conversation. */
    @Size(max = 64)
    private String clientMsgId;
    /** TEXT / MARKDOWN / CARD / FILE / IMAGE / SYSTEM。 */
    private String type;
    /** Body/card/attachment content; persisted as JSON. */
    private Object content;
    private Long replyToId;
    private List<MentionDTO> mentions;
    /** Target clarification card when this text answers a parked Router clarification. */
    private String clarificationInputId;
}

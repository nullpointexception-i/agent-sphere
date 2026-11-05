package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class MessageVO implements Serializable {
    private Long id;
    private Long conversationId;
    private Long seq;
    private String senderType;
    private Long senderId;
    private String clientMsgId;
    private String type;
    private Object content;
    private Long replyToId;
    private Object mentions;
    private String status;
    private String createdAt;
}

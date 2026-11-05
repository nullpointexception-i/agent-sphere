package com.buukle.agent.im.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ParticipantVO implements Serializable {
    private Long id;
    private Long conversationId;
    private String participantType;
    private Long participantId;
    private String role;
    private String displayName;
    private String username;
    private String avatar;
    private Boolean muted;
    private String joinedAt;
}

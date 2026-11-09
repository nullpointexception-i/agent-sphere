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
    private Boolean autoAdded;
    /** Owner user id of an AGENT participant; null for users. Used to nest avatars under their owner. */
    private Long ownerUserId;
    private String joinedAt;
    /** External IdP provider code (for example bole); null for local users. */
    private String ssoProviderCode;
    /** External IdP display username, shown as provider@subject. */
    private String ssoSubject;
}

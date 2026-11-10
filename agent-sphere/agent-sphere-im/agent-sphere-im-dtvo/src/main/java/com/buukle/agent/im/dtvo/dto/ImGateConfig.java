package com.buukle.agent.im.dtvo.dto;

import lombok.Data;

import java.io.Serializable;

/** Group trigger policy stored in im_conversation.gate_config. */
@Data
public class ImGateConfig implements Serializable {

    public static final String TRIGGER_MENTION = "MENTION";
    public static final String TRIGGER_RELEVANCE = "RELEVANCE";
    public static final String TRIGGER_ALL = "ALL";

    /** MENTION / RELEVANCE / ALL */
    private String trigger;

    /** Whether ordinary members may invite users. Missing means true for backward compatibility. */
    private Boolean allowMemberInvite;

    /**
     * Strict clarification answers: only the asker, the avatar owner and group admins.
     * Missing means false (any member may answer).
     */
    private Boolean clarificationStrict;
}

package com.buukle.agent.im.dtvo.vo;

import com.buukle.agent.im.dtvo.dto.MentionDTO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Typed raw metadata attached to an IM event for group routing. */
@Data
public class ImEventMetaVO implements Serializable {
    private String type;
    private List<MentionDTO> mentions;
    private List<Long> agentIds;
    private String conversationType;
    private String conversationTitle;
    private String purpose;
    private String gateConfig;
    private String entityType;
    private Long entityId;
    private String entityPurpose;
    /** Pre-resolved title/status/summary and domain attributes for the prompt. */
    private Map<String, String> entityContext;
    private String locale;
    /** Human members of the conversation; avatars are not members. */
    private List<Long> memberUserIds;
    /** Author of this message, so an avatar knows who is talking to it. */
    private Long speakerId;
    private String speakerName;
}

package com.buukle.agent.im.dtvo.vo;

import com.buukle.agent.im.dtvo.dto.MentionDTO;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

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
}

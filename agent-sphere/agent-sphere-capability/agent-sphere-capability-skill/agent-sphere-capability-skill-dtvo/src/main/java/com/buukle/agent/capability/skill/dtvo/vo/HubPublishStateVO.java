package com.buukle.agent.capability.skill.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Publish state of one local skill, used by the "My skills" list badges. */
@Data
public class HubPublishStateVO implements Serializable {

    private Long skillId;
    private Boolean published;
    private Long hubSkillId;
    private Integer hubVersion;
    private Integer localVersion;
    /** The local skill is ahead of the published snapshot. */
    private Boolean hasUnpublished;
}

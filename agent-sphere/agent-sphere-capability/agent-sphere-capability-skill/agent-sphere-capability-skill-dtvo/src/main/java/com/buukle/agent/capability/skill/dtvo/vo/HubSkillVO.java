package com.buukle.agent.capability.skill.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * Tenant hub row plus the viewer-specific state, so the list needs no follow-up calls:
 * whether the current user follows it, installed a copy, or owns the source skill.
 */
@Data
public class HubSkillVO implements Serializable {

    private Long id;
    private Long tenantId;
    private Long sourceSkillId;
    private Long publisherUserId;
    private String publisherName;
    private String name;
    private String description;
    private String definition;

    /** Hub content version: increases on every publish/update, never decreases. */
    private Integer version;
    /** Local version that was published last (null when the source skill is gone). */
    private Integer sourceVersion;
    /** Current local version of the source skill, when it is visible to the viewer. */
    private Integer localVersion;
    /** True when the local skill advanced past sourceVersion and is not published yet. */
    private Boolean hasUnpublished;

    private Integer installCount;
    private Integer followCount;

    private Boolean followed;
    private Long installedSkillId;
    /** True when the viewer owns the source skill (only the owner can publish updates). */
    private Boolean mine;

    private String createdAt;
    private String updatedAt;
}

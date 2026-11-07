package com.buukle.agent.capability.skill.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SkillVO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private String definition;
    private String status;
    private String visibility;
    private Long originSkillId;
    private Integer installCount;
    private Integer version;
    private Integer originVersion;
    private Boolean autoUpdate;
    /** Last time the copy was aligned with its origin (display snapshot); equals the install time right after installation */
    private String syncedAt;
    /** Origin version the copy was last synced to (display snapshot) */
    private Integer syncedFromVersion;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}

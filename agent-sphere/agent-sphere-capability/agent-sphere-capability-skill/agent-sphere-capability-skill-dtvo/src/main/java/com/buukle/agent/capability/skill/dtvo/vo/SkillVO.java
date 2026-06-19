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
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}

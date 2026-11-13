package com.buukle.agent.project.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RequirementActivityVO {
    private Long id;
    private Long requirementId;
    private Long actorUserId;
    private String activityType;
    private String fromValue;
    private String toValue;
    private LocalDateTime createdAt;
}

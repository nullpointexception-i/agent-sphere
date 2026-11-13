package com.buukle.agent.project.dtvo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class RequirementVO {
    private Long id;
    private Long projectId;
    private String projectName;
    private String requirementKey;
    private String title;
    private String description;
    private String status;
    private Integer priority;
    private Long ownerUserId;
    private String ownerName;
    private Long assigneeUserId;
    private String assigneeName;
    private LocalDate startDate;
    private LocalDate dueDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

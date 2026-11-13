package com.buukle.agent.project.dtvo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class ProjectVO {
    private Long id;
    private String projectKey;
    private String name;
    private String description;
    private Long ownerUserId;
    private String ownerName;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long requirementSeq;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

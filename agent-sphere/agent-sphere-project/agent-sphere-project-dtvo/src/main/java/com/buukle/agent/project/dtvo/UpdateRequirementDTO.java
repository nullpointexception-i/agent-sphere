package com.buukle.agent.project.dtvo;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateRequirementDTO {
    @Size(max = 256)
    private String title;
    private String description;
    private Integer priority;
    private Long assigneeUserId;
    private LocalDate startDate;
    private LocalDate dueDate;
}

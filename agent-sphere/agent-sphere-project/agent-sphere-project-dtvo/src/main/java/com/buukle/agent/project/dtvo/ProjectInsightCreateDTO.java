package com.buukle.agent.project.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProjectInsightCreateDTO {
    private Long requirementId;
    @Size(max = 32)
    private String insightType;
    @Size(max = 16)
    private String severity;
    @Size(max = 255)
    private String title;
    @NotBlank
    private String content;
}

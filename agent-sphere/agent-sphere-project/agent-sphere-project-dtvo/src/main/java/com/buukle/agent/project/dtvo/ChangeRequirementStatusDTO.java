package com.buukle.agent.project.dtvo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangeRequirementStatusDTO {
    @NotBlank
    private String status;
}

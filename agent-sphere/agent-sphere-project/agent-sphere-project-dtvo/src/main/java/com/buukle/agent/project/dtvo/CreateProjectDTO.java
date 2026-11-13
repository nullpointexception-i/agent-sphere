package com.buukle.agent.project.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateProjectDTO {
    @NotBlank
    @Size(max = 128)
    private String name;
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
}

package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class TrainingMessageDTO implements Serializable {

    @NotBlank
    @Size(max = 5000)
    private String message;

    /** WRITE / READ_ONLY; empty uses the session default. */
    private String runMode;

    private Long modelRouteId;

    private String reasoningEffort;
}

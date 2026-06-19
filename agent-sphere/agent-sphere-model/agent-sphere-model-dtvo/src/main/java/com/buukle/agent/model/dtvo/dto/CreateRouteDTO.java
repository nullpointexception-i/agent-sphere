package com.buukle.agent.model.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.io.Serializable;

@Data
public class CreateRouteDTO implements Serializable {
    @NotNull(message = "providerId can't be null")
    private Long providerId;
    @NotBlank(message = "modelName can't be blank")
    @Size(min = 1, max = 64)
    private String modelName;
    private Integer weight;
    @Size(max = 255)
    private String fallbackIds;
    private Long maxInputTokens;
    private Long maxOutputTokens;
    private String company;
}

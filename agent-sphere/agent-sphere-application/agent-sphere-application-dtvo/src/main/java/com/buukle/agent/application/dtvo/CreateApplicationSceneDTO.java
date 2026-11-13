package com.buukle.agent.application.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateApplicationSceneDTO {
    @NotBlank
    @Size(max = 64)
    private String sceneKey;
    @Size(max = 128)
    private String name;
    @NotBlank
    private String sceneType;
    private String config;
    private Integer sortOrder;
}

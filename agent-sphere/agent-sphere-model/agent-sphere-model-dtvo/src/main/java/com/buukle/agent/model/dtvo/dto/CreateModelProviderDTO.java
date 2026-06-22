package com.buukle.agent.model.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateModelProviderDTO implements Serializable {
    @NotBlank(message = "name can't be blank")
    @Size(min = 1, max = 64)
    private String name;
    @NotBlank
    @Size(min = 1, max = 500)
    private String baseUrl;
    private Long apiKeyId;
    @Size(max = 5000)
    private String config;
}

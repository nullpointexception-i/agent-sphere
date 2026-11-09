package com.buukle.agent.model.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/** Ad-hoc provider/model connectivity test used by tenant creation. */
@Data
public class ModelConnectionTestDTO implements Serializable {

    @NotBlank
    @Size(max = 64)
    private String company;

    @NotBlank
    @Size(max = 500)
    private String baseUrl;

    @NotBlank
    @Size(max = 2000)
    private String apiKey;

    @NotBlank
    @Size(max = 64)
    private String modelName;

    private Integer maxTokens;
}

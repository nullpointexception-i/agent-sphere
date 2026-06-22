package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateInstanceDTO implements Serializable {
    @NotBlank(message = "name can't be blank")
    @Size(min = 1, max = 64)
    private String name;
    @Size(max = 255)
    private String description;
    @Size(max = 5000)
    private String systemPrompt;
    private Long modelRouteId;
    @Size(max = 5000)
    private String customInstructions;
    @Size(max = 3000000)
    private String image;
}

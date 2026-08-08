package com.buukle.agent.completions.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateCompletionsDTO implements Serializable {
    @NotBlank(message = "name can't be blank")
    @Size(min = 1, max = 200)
    private String name;
    @Size(max = 2000)
    private String description;
    private Long modelRouteId;
    private String inputSchema;
    private String outputSchema;
    private String config;
    @Size(max = 500)
    private String remark;
    @Size(max = 64)
    private String businessType;
}

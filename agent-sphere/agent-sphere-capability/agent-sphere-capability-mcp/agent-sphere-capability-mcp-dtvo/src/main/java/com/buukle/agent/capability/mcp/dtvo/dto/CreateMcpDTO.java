package com.buukle.agent.capability.mcp.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateMcpDTO implements Serializable {
    @NotBlank(message = "name can't be blank")
    @Size(min = 1, max = 64)
    private String name;
    @Size(max = 255)
    private String description;
    @NotBlank(message = "serverUrl can't be blank")
    @Size(min = 1, max = 500)
    private String serverUrl;
    @NotBlank
    @Size(min = 1, max = 64)
    private String serverType;
    @Size(max = 5000)
    private String authConfig;
    @Size(max = 5000)
    private String toolDefinitions;
}

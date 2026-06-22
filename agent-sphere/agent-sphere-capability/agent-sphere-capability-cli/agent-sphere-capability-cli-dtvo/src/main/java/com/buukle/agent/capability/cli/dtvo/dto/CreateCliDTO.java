package com.buukle.agent.capability.cli.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateCliDTO implements Serializable {
    @NotBlank(message = "name can't be blank")
    @Size(min = 1, max = 64)
    private String name;
    @NotBlank(message = "commandTemplate can't be blank")
    @Size(min = 1, max = 500)
    private String commandTemplate;
    @Size(max = 5000)
    private String paramSchema;
    @NotBlank
    @Size(min = 1, max = 500)
    private String workingDir;
}

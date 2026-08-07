package com.buukle.agent.tasks.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class CreateTaskDTO implements Serializable {
    @NotBlank(message = "goal can't be blank")
    @Size(min = 1, max = 5000)
    private String goal;
    private Map<String, Object> context;
    private Map<String, Object> expectedOutput;
    private Map<String, Object> config;
    private Long instanceId;
    @Size(max = 500)
    private String callbackUrl;
}

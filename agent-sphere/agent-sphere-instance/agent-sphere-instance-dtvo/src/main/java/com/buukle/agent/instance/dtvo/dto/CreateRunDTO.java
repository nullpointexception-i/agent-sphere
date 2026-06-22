package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateRunDTO implements Serializable {
    @NotNull(message = "sessionId can't be null")
    private Long sessionId;
    @NotBlank(message = "type can't be blank")
    @Size(min = 1, max = 64)
    private String type;
    @NotBlank(message = "userMessage can't be blank")
    @Size(min = 1, max = 5000)
    private String userMessage;
}

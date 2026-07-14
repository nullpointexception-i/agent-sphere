package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class ClarifyDTO implements Serializable {
    @NotBlank(message = "response is required")
    private String response;
    private String clarificationId;
}

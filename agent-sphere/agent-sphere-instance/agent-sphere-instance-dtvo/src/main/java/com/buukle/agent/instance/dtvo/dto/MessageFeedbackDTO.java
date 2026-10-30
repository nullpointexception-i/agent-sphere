package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class MessageFeedbackDTO implements Serializable {
    @NotBlank
    private String value;
}

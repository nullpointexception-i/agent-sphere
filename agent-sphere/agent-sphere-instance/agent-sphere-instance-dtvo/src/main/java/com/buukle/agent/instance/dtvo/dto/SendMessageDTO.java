package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class SendMessageDTO implements Serializable {
    @NotBlank(message = "message can't be blank")
    @Size(min = 1, max = 5000)
    private String message;
    private Long modelRouteId;
    private String delivery;
}

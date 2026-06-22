package com.buukle.agent.model.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class CreateApiKeyDTO implements Serializable {
    @NotNull(message = "providerId can't be null")
    private Long providerId;
    @Size(max = 255)
    private String alias;
    @NotBlank(message = "keyValue can't be blank")
    @Size(min = 1, max = 2000)
    private String keyValue;
    private LocalDateTime expiresAt;
}

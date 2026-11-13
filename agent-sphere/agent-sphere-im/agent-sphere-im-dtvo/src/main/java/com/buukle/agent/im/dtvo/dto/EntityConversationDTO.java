package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EntityConversationDTO {
    @NotBlank
    private String bizType;
    @NotNull
    private Long bizId;
    @NotBlank
    private String bizPurpose;
}

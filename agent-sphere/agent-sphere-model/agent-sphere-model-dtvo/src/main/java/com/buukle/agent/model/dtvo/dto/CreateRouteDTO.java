package com.buukle.agent.model.dtvo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateRouteDTO implements Serializable {
    @NotNull(message = "providerId can't be null")
    private Long providerId;
    @NotBlank(message = "modelName can't be blank")
    @Size(min = 1, max = 64)
    private String modelName;
    private Integer weight;
    @Size(max = 255)
    private String fallbackIds;
    @NotNull(message = "maxInputTokens can't be null")
    @Min(value = 1, message = "maxInputTokens must be positive")
    private Long maxInputTokens;
    @NotNull(message = "maxOutputTokens can't be null")
    @Min(value = 1, message = "maxOutputTokens must be positive")
    private Long maxOutputTokens;
    private String company;
    private Boolean supportsAttachment;
    /** STRICT_FINISH by default; IMPLICIT_TEXT only for compatible conversational models. */
    private String completionProtocol;
    /** Mark this route as the owner's default route. */
    private Boolean isDefault;
    /** Reasoning strength override: NULL = inherit from provider. */
    private Boolean reasoningSupported;
    private String reasoningMode;
    /** JSON array string, e.g. ["low","high","max"]. */
    private String reasoningLevels;
    private java.math.BigDecimal reasoningAnswerReserveRatio;
}

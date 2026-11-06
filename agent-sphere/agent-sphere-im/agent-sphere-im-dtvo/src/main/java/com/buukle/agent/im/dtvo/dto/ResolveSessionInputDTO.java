package com.buukle.agent.im.dtvo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** Per-call decisions for a Router tool-approval card. */
@Data
public class ResolveSessionInputDTO implements Serializable {
    @NotEmpty(message = "decisions is required")
    @Valid
    private List<Decision> decisions;

    @Data
    public static class Decision implements Serializable {
        @NotBlank(message = "callId is required")
        private String callId;
        @NotBlank(message = "decision is required")
        private String decision;
    }
}

package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ResolveApprovalDTO implements Serializable {

    @NotEmpty
    @Valid
    private List<Decision> decisions;

    @Data
    public static class Decision implements Serializable {
        @NotBlank
        private String callId;
        @NotBlank
        private String decision;
    }
}

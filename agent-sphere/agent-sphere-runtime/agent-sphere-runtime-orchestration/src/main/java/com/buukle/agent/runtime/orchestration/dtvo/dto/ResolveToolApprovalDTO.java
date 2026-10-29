package com.buukle.agent.runtime.orchestration.dtvo.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ResolveToolApprovalDTO implements Serializable {
    private List<Decision> decisions;

    @Data
    public static class Decision implements Serializable {
        private String callId;
        private String decision;
    }
}

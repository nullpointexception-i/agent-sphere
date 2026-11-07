package com.buukle.agent.admin.dtvo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class UserTenantAssignReq {

    @NotNull
    private Long userId;

    @Valid
    private List<Assignment> assignments;

    @Data
    public static class Assignment {
        @NotNull
        private Long tenantId;
        private String visibility;
        private Boolean isCurrent;
    }
}

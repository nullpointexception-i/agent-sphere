package com.buukle.agent.admin.dtvo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class AssignPermissionDTO {
    @NotNull
    private Long roleId;
    private List<Long> permissionIds;
}

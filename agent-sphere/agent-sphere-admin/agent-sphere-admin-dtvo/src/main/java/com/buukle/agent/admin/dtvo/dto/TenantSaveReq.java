package com.buukle.agent.admin.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TenantSaveReq {
    @NotBlank
    private String code;
    @NotBlank
    private String name;
    private Long pid;
    private Integer sortOrder;
    private String status;
    private String remark;
}

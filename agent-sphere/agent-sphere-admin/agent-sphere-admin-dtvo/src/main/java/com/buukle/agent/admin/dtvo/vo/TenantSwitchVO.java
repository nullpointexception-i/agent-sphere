package com.buukle.agent.admin.dtvo.vo;

import lombok.Data;

@Data
public class TenantSwitchVO {
    private Long currentTenantId;
    private String currentTenantName;
}

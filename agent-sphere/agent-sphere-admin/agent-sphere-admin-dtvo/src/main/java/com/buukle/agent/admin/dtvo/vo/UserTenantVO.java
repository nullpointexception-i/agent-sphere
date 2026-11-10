package com.buukle.agent.admin.dtvo.vo;

import lombok.Data;

@Data
public class UserTenantVO {
    private Long tenantId;
    private String tenantCode;
    private String tenantName;
    private String tenantStatus;
    private String visibility;
    private Boolean isCurrent;
    private Boolean manageable;
    /** Tenant's current GROUP_ROUTER instance (group chat routing), when set. */
    private Long currentInstanceId;
}

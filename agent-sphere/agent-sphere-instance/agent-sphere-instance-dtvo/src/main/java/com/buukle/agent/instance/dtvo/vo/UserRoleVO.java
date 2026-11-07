package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

/** Role assignment shown on the user list, including the role's source tenant. */
@Data
public class UserRoleVO {
    private Long id;
    private String code;
    private String name;
    private Long tenantId;
    private String tenantName;
    private Boolean platformRole;
}

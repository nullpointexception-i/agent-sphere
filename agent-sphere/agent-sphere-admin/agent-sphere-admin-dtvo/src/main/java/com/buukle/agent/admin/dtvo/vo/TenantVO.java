package com.buukle.agent.admin.dtvo.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TenantVO {
    private Long id;
    private String code;
    private String name;
    private Long pid;
    private String parentName;
    private Integer sortOrder;
    private String status;
    private String remark;
    /** Tenant-scoped model route used by the group chat router. */
    private Long groupRouterModelRouteId;
    private LocalDateTime createdAt;
    /** Whether the current operator can manage this tenant. */
    private Boolean manageable;
    private List<TenantVO> children;
}

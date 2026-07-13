package com.buukle.agent.admin.dtvo.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SysRoleVO {
    private Long id;
    private String name;
    private String code;
    private String description;
    private LocalDateTime createdAt;
    private List<Long> permissionIds;
}

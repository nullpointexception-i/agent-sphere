package com.buukle.agent.admin.dtvo.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SysPermissionVO {
    private Long id;
    private String name;
    private String code;
    private String type;
    private Long parentId;
    private Integer sort;
    private String description;
    private LocalDateTime createdAt;
    private List<SysPermissionVO> children;
    private boolean assigned;
}

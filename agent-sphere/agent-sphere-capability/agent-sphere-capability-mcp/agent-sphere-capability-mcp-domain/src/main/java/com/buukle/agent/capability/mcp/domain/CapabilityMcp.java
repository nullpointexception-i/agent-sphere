package com.buukle.agent.capability.mcp.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("capability_mcp")
public class CapabilityMcp {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String serverUrl;
    private String serverType;
    private String authConfig;
    private String toolDefinitions;
    private String status;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package com.buukle.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_system_config")
public class SystemConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String configGroup;
    private String configKey;
    private String configValue;
    private Boolean isSecret;
    private String description;
    @TableLogic
    private Boolean deleteFlag;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package com.buukle.agent.model.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("agent_model_provider")
public class AgentModelProvider {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String baseUrl;
    @TableField("api_key_id")
    private Long apiKeyId;
    private String config;
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

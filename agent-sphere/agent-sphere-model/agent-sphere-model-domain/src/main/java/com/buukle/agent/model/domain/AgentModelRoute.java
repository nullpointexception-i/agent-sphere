package com.buukle.agent.model.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_model_route")
public class AgentModelRoute {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String modelName;
    private Integer weight;
    private String fallbackIds;
    private Long maxInputTokens;
    private Long maxOutputTokens;
    private String status;
    private String company;
    private Boolean supportsAttachment;
    /** Completion contract for this model route: STRICT_FINISH / IMPLICIT_TEXT. */
    private String completionProtocol;
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

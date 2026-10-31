package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_document")
public class AgentDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    private String contentType;
    /** 文档内容版本，每次 update 自增（展示用）。 */
    private Integer version;
    private Long sessionId;
    private Long instanceId;
    private Long runId;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    private String shareToken;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

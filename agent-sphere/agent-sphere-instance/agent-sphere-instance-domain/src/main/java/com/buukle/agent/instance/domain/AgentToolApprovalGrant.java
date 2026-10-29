package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_tool_approval_grant")
public class AgentToolApprovalGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    private String toolRef;
    private String scope;
    private String approvedBy;
    private String status;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableLogic
    private Boolean deleteFlag;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("agent_user_in_loop_record")
public class AgentUserInLoopRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stepId;
    private Long runId;
    private Long sessionId;
    private String interactionType;
    private String status;
    private String prompt;
    private String response;
    private String respondedBy;
    private String result;
    private String comment;
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

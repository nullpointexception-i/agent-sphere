package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_run")
public class AgentRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String type;
    private String userMessage;
    private String assistantReply;
    private String reasoning;
    private String intentClassification;
    private Long taskId;
    private String status;
    /** 命中循环次数上限被强收口（任务守卫据此判失败，避免"半成品"冒充完成）。 */
    private Boolean loopCapped;
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

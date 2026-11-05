package com.buukle.agent.superagent.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_event")
public class AgentEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long threadId;
    private Long seq;
    private String kind;
    private String externalId;
    private String dedupeKey;
    private String payload;
    private String status;
    private String groupDecision;
    private String groupReason;
    private LocalDateTime groupProcessedAt;
    private LocalDateTime receivedAt;
    private Integer attempts;
    private LocalDateTime nextRetryAt;
    private String actorType;
    private Long actorId;
    private String remark;
    @TableLogic(value = "false", delval = "true")
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

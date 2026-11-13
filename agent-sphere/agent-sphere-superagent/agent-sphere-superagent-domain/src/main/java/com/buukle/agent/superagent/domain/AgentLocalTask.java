package com.buukle.agent.superagent.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One local execution assigned to a desktop runtime. */
@Data
@TableName("agent_local_task")
public class AgentLocalTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private Long superAgentId;
    private Long runtimeId;
    private Long threadId;
    /** Business owner of a non-IM task, e.g. PROJECT_MENTION. */
    private String businessType;
    private Long businessId;
    private String status;
    private String inputPayload;
    private String sessionRef;
    private Long lastSeq;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private Integer attempts;
    private LocalDateTime deadlineAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorCode;
    private String errorMessage;
    private Long replyOutboundId;
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

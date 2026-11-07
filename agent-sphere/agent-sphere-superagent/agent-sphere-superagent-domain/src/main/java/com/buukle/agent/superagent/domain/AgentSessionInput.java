package com.buukle.agent.superagent.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Router parked input (clarification / L2 tool approval) bridged to the IM DM as a card.
 *
 * @see com.buukle.agent.superagent.spi.SessionInputSpi
 */
@Data
@TableName("agent_session_input")
public class AgentSessionInput {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
    private Long superAgentId;
    private Long threadId;
    private Long sessionId;
    private Long runId;
    /** CLARIFICATION / APPROVAL */
    private String inputType;
    /** clarificationId / approvalId */
    private String inputId;
    private String title;
    private String description;
    private String options;
    private Boolean multiple;
    private String toolCallsJson;
    /** PENDING / ANSWERED / CANCELLED */
    private String status;
    private Long cardOutboundId;
    private String respondedBy;
    private LocalDateTime respondedAt;
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

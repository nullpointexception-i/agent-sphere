package com.buukle.agent.project.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pm_requirement_comment")
public class PmRequirementComment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requirementId;
    private Long userId;
    private String content;
    /** JSON array of attachment metadata. */
    private String attachments;
    /** USER / ASSISTANT / AVATAR, see CommentAuthorType. */
    private String authorType;
    private String authorName;
    private Long parentCommentId;
    private Long sourceSessionId;
    private Long sourceRunId;
    private String sourceBatchId;
    /** Stable idempotency key within one assistant run. */
    private String actionKey;
    private String status;
    private String remark;
    @TableLogic(value = "false", delval = "true")
    private Boolean deleteFlag;
    @TableField(fill = FieldFill.INSERT)
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

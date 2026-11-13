package com.buukle.agent.project.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Structured @ mention extracted from one requirement comment. */
@Data
@TableName("pm_requirement_comment_mention")
public class PmRequirementCommentMention {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long commentId;
    /** USER or AVATAR, see CommentMentionType. */
    private String mentionType;
    private Long targetUserId;
    private Long targetAgentId;
    private String status;
    private LocalDateTime processedAt;
    private Long replyCommentId;
    private String errorMessage;
    private Integer attempts;
    private LocalDateTime nextRetryAt;
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

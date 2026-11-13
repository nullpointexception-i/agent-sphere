package com.buukle.agent.project.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only shared project context. The canonical project-assistant session and every user
 * fork receive the latest entries as a system context block.
 */
@Data
@TableName("pm_project_context_event")
public class PmProjectContextEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long requirementId;
    private String eventType;
    private String summary;
    private Long sourceSessionId;
    private Long canonicalSessionId;
    private Long actorUserId;
    private String status;
    private String remark;
    /** Baseline pipeline state, see ProjectAnalysisStatus. */
    private String analysisStatus;
    private String analysisBatchId;
    private Long analysisRunId;
    private Integer analysisAttempts;
    private LocalDateTime analysisNextRetryAt;
    private LocalDateTime analysisProcessedAt;
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

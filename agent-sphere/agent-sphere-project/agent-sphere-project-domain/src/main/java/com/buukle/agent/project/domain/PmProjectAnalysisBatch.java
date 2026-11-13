package com.buukle.agent.project.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One canonical baseline analysis batch over a slice of project-context events. */
@Data
@TableName("pm_project_analysis_batch")
public class PmProjectAnalysisBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    /** Stable, content-derived key used for idempotent batch creation. */
    private String batchKey;
    private String status;
    private Integer eventCount;
    private Integer charCount;
    private Long canonicalSessionId;
    private Long runId;
    private Integer attempts;
    private LocalDateTime nextRetryAt;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
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

package com.buukle.agent.tasks.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 任务契约 artifact（两阶段提炼的结构化输出，可扩展 artifact_type）。 */
@Data
@TableName("agent_task_artifact")
public class AgentTaskArtifact {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String artifactType;
    private String content;
    private String schemaRef;
    private Long runId;
    private String status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

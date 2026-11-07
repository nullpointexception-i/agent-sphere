package com.buukle.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Generic file store: database-backed binary files (biz_key + file_key unique, PG bytea). */
@Data
@TableName("agent_file_store")
public class AgentFileStore {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizKey;
    private String fileKey;
    private String fileName;
    private byte[] content;
    private Long sizeBytes;
    private String contentType;
    private String status;
    private String remark;
    @TableLogic
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
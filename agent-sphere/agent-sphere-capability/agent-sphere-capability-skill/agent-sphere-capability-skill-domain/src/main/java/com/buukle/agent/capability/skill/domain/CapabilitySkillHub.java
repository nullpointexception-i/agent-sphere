package com.buukle.agent.capability.skill.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Tenant-owned Skill Hub entry: a snapshot published from one local skill.
 *
 * <p>{@code sourceSkillId} keeps the lineage back to the author's local skill. A hub entry is
 * independent once published: deleting the local skill does not remove the hub content, it only
 * stops future updates from that source.
 *
 * <p>{@code version} is the hub's own counter. Publishing and every content update (including
 * restoring older content) increment it by one; it never decreases and there is no withdraw state.
 * {@code sourceVersion} records which local version was published last, so the UI can show
 * "has unpublished changes" when the local skill advances past it.
 */
@Data
@TableName("capability_skill_hub")
public class CapabilitySkillHub {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long sourceSkillId;
    private Long publisherUserId;
    private String name;
    private String description;
    private String definition;
    private Integer version;
    private Integer sourceVersion;
    private Integer installCount;
    private Integer followCount;

    @TableLogic
    private Boolean deleteFlag;

    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.buukle.agent.capability.skill.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_skill")
public class CapabilitySkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String definition;
    private String status;
    /** Hub visibility: PRIVATE = author only, PUBLIC = listed in the hub. */
    private String visibility;
    /** Fork lineage: an installed copy points back to the source skill id; null for originals. */
    private Long originSkillId;
    /** Hub entry this copy was installed from (null for originals and legacy copies). */
    private Long hubSkillId;
    /** Number of times this skill has been copied/installed (accumulated on the source row). */
    private Integer installCount;
    /** Content version: bumped on every content edit of the source skill; 1 on creation. */
    private Integer version;
    /** Source version recorded when an installed copy was last synced; only meaningful for copies (originSkillId non-null). */
    private Integer originVersion;
    /** Whether an installed copy follows source versions automatically; only meaningful for copies. */
    private Boolean autoUpdate;
    /**
     * Last time the copy was aligned with its origin (display snapshot): set to the install time on
     * install and refreshed when syncFromOrigin succeeds. Always equal in value to originVersion but
     * independent of it - originVersion is the optimistic-lock field of the conditional UPDATE, so a
     * failed update desyncs it from the UI.
     */
    private LocalDateTime syncedAt;
    /** Origin version the copy was last synced to (display snapshot, read-only). */
    private Integer syncedFromVersion;
    @TableLogic
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

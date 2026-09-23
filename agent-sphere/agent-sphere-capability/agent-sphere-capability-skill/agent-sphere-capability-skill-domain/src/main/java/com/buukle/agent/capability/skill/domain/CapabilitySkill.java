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
    /** hub 可见性：PRIVATE=仅作者，PUBLIC=hub 公开。 */
    private String visibility;
    /** fork 血缘：安装副本指回源 skill id；原生为 null。 */
    private Long originSkillId;
    /** 被复制安装次数（源行累加）。 */
    private Integer installCount;
    /** 内容版本：源技能每次内容编辑自增；创建时为 1。 */
    private Integer version;
    /** 已安装副本上次同步时记录的源版本；仅副本（originSkillId 非空）有意义。 */
    private Integer originVersion;
    /** 已安装副本是否随源版本自动更新；仅副本有意义。 */
    private Boolean autoUpdate;
    @TableLogic
    private Boolean deleteFlag;
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

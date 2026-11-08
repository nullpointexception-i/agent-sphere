package com.buukle.agent.capability.skill.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * User subscription to a tenant hub entry. Following creates no local skill copy: the bound
 * instance resolves the hub content at run time, which is the "never modify it" mode.
 */
@Data
@TableName("capability_skill_follow")
public class CapabilitySkillFollow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long hubSkillId;

    @TableLogic
    private Boolean deleteFlag;

    private LocalDateTime createdAt;
}

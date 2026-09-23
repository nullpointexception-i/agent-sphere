package com.buukle.agent.capability.skill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;

public interface CapabilitySkillService extends IService<CapabilitySkill>, CapabilitySkillSpi {

    /**
     * 按源版本同步一个已安装副本（供 SkillAutoUpdateSweeper 调度调用）：
     * 源已删除/非公开/版本未落后返回 false；发生同步返回 true。
     */
    boolean syncFromOrigin(CapabilitySkill copy);
}

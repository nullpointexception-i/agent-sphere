package com.buukle.agent.capability.skill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.spi.CapabilitySkillSpi;

public interface CapabilitySkillService extends IService<CapabilitySkill>, CapabilitySkillSpi {
}

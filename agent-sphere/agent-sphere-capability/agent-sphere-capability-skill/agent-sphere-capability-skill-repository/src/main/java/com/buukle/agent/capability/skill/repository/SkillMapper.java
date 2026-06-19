package com.buukle.agent.capability.skill.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkillMapper extends BaseMapper<CapabilitySkill> {}

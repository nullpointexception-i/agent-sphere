package com.buukle.agent.project.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.project.domain.PmProjectMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PmProjectMemberMapper extends BaseMapper<PmProjectMember> {
}

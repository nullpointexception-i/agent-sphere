package com.buukle.agent.application.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.application.domain.BizApplicationMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BizApplicationMemberMapper extends BaseMapper<BizApplicationMember> {
}

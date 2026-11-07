package com.buukle.agent.admin.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.admin.domain.SysTenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {
}

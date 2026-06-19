package com.buukle.agent.model.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.model.domain.AgentModelRoute;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RouteMapper extends BaseMapper<AgentModelRoute> {}

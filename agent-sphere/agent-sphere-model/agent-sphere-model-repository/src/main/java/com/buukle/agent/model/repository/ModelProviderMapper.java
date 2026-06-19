package com.buukle.agent.model.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.model.domain.AgentModelProvider;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelProviderMapper extends BaseMapper<AgentModelProvider> {}

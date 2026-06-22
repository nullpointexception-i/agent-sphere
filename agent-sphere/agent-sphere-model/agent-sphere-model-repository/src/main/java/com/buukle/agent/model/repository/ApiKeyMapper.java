package com.buukle.agent.model.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.model.domain.AgentApiKey;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiKeyMapper extends BaseMapper<AgentApiKey> {
}

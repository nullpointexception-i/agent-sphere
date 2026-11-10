package com.buukle.agent.superagent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.superagent.domain.AgentLocalSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentLocalSessionMapper extends BaseMapper<AgentLocalSession> {
}

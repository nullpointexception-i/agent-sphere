package com.buukle.agent.tasks.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.tasks.domain.AgentTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTask> {
}

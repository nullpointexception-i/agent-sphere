package com.buukle.agent.tasks.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.tasks.domain.AgentTaskArtifact;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentTaskArtifactMapper extends BaseMapper<AgentTaskArtifact> {
}

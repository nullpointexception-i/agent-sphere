package com.buukle.agent.completions.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.completions.domain.AgentCompletions;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CompletionsMapper extends BaseMapper<AgentCompletions> {
}

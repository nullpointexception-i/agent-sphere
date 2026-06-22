package com.buukle.agent.capability.mcp.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface McpMapper extends BaseMapper<CapabilityMcp> {
}

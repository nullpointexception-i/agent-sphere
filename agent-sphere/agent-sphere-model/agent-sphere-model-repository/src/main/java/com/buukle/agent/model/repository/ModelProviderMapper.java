package com.buukle.agent.model.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.model.domain.AgentModelProvider;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelProviderMapper extends BaseMapper<AgentModelProvider> {

    /** 1 when any tenant's current instance binds a route of this provider. */
    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(*)
            FROM sys_tenant t
            JOIN agent_instance i ON i.id = t.current_instance_id
            JOIN agent_model_route r ON r.id = i.model_route_id
            WHERE r.provider_id = #{providerId}
              AND t.delete_flag = 0
              AND i.delete_flag = 0
              AND r.delete_flag = 0
            """)
    int countCurrentInstanceUsage(@org.apache.ibatis.annotations.Param("providerId") Long providerId);
}

package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentInstance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InstanceMapper extends BaseMapper<AgentInstance> {

    /** 1 when this instance is the tenant's current GROUP_ROUTER. */
    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(*) FROM sys_tenant
            WHERE current_instance_id = #{instanceId} AND delete_flag = 0
            """)
    int countAsCurrent(@org.apache.ibatis.annotations.Param("instanceId") Long instanceId);

    /** The tenant's current GROUP_ROUTER instance (tenant.current_instance_id). */
    @org.apache.ibatis.annotations.Select("""
            SELECT i.* FROM agent_instance i
            JOIN sys_tenant t ON t.current_instance_id = i.id
            WHERE t.id = #{tenantId}
              AND t.delete_flag = 0
              AND i.delete_flag = 0
            LIMIT 1
            """)
    AgentInstance selectCurrentByTenant(@org.apache.ibatis.annotations.Param("tenantId") Long tenantId);
}

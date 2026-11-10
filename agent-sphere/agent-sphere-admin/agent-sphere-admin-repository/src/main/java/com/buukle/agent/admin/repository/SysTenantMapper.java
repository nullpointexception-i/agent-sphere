package com.buukle.agent.admin.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.admin.domain.SysTenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    /** 1 when the instance is a TENANT-owned GROUP_ROUTER of this tenant. */
    @Select("""
            SELECT COUNT(*) FROM agent_instance
            WHERE id = #{instanceId}
              AND tenant_id = #{tenantId}
              AND instance_role = 'GROUP_ROUTER'
              AND owner_scope = 'TENANT'
              AND delete_flag = 0
            """)
    int countTenantRouterInstance(@Param("tenantId") Long tenantId,
                                  @Param("instanceId") Long instanceId);
}

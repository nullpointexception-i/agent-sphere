package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentInstanceCapability;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InstanceCapabilityMapper extends BaseMapper<AgentInstanceCapability> {
    @Delete("DELETE FROM agent_instance_capability WHERE instance_id = #{instanceId} AND capability_type = #{capabilityType} AND capability_id = #{capabilityId}")
    int hardDeleteByUniqueKey(@Param("instanceId") Long instanceId, @Param("capabilityType") String capabilityType, @Param("capabilityId") Long capabilityId);
}

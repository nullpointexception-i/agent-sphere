package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentAuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentAuditLogMapper extends BaseMapper<AgentAuditLog> {

    @Delete("DELETE FROM sys_audit_log WHERE delete_flag = 0 AND action IN ('PAGE_VIEW','PAGE_EXIT','CLICK','SELECT','DWELL') AND created_at < NOW() - INTERVAL '1 day' * #{days}")
    int deleteFrontendEventsOlderThan(@Param("days") int days);

    @Delete("DELETE FROM sys_audit_log WHERE delete_flag = 0 AND action NOT IN ('PAGE_VIEW','PAGE_EXIT','CLICK','SELECT','DWELL') AND created_at < NOW() - INTERVAL '1 day' * #{days}")
    int deleteBackendEventsOlderThan(@Param("days") int days);
}

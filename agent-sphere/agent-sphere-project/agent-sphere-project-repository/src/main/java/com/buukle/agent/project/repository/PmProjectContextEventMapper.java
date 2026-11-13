package com.buukle.agent.project.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.project.domain.PmProjectContextEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PmProjectContextEventMapper extends BaseMapper<PmProjectContextEvent> {

    /** Projects with at least one baseline event whose retry time has arrived. */
    @Select("""
            SELECT DISTINCT project_id
            FROM pm_project_context_event
            WHERE delete_flag = false
              AND analysis_status = 'PENDING'
              AND project_id IS NOT NULL
              AND (analysis_next_retry_at IS NULL OR analysis_next_retry_at <= NOW())
            ORDER BY project_id
            LIMIT #{limit}
            """)
    List<Long> selectPendingProjectIds(@Param("limit") int limit);
}

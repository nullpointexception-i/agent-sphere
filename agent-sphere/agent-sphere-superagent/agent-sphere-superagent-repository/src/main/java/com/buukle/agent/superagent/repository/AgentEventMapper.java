package com.buukle.agent.superagent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.superagent.domain.AgentEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {

    @Select("""
            SELECT thread_id
            FROM agent_event
            WHERE group_processed_at IS NULL
              AND (next_retry_at IS NULL OR next_retry_at <= now())
              AND delete_flag = FALSE
            GROUP BY thread_id
            ORDER BY MIN(received_at)
            LIMIT #{limit}
            """)
    List<Long> selectPendingThreadIds(@Param("limit") int limit);

    @Select("""
            SELECT MIN(received_at)
            FROM agent_event
            WHERE thread_id = #{threadId}
              AND group_processed_at IS NULL
              AND delete_flag = FALSE
            """)
    java.time.LocalDateTime selectOldestPendingReceivedAt(@Param("threadId") Long threadId);

    @Select("""
            SELECT COUNT(*)
            FROM agent_event
            WHERE thread_id = #{threadId}
              AND group_processed_at IS NULL
              AND delete_flag = FALSE
            """)
    long countPending(@Param("threadId") Long threadId);
}

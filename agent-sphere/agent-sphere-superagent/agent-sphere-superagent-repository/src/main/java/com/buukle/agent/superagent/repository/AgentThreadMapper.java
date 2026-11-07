package com.buukle.agent.superagent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.superagent.domain.AgentThread;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentThreadMapper extends BaseMapper<AgentThread> {

    /** Atomically increments the thread's event sequence. */
    @Select("""
            UPDATE agent_thread
            SET event_seq = event_seq + 1, updated_at = now()
            WHERE id = #{id} AND delete_flag = FALSE
            RETURNING event_seq
            """)
    Long nextEventSeq(@Param("id") Long id);
}

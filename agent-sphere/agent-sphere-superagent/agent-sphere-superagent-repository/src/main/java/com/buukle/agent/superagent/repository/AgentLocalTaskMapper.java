package com.buukle.agent.superagent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.superagent.domain.AgentLocalTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AgentLocalTaskMapper extends BaseMapper<AgentLocalTask> {

    @Update("""
            UPDATE agent_local_task
            SET status = 'RUNNING', lease_token = #{leaseToken}, lease_until = #{leaseUntil},
                started_at = COALESCE(started_at, now()), attempts = attempts + 1, updated_at = now()
            WHERE id = #{id} AND runtime_id = #{runtimeId}
              AND status = 'PENDING' AND delete_flag = FALSE
            """)
    int claim(@Param("id") Long id, @Param("runtimeId") Long runtimeId,
              @Param("leaseToken") String leaseToken, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE agent_local_task
            SET lease_until = #{leaseUntil}, updated_at = now()
            WHERE id = #{id} AND runtime_id = #{runtimeId}
              AND status IN ('CLAIMED', 'RUNNING') AND delete_flag = FALSE
            """)
    int renewLease(@Param("id") Long id, @Param("runtimeId") Long runtimeId,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE agent_local_task
            SET status = 'SUCCEEDED', finished_at = now(), reply_outbound_id = #{outboundId},
                session_ref = #{sessionRef}, error_code = NULL, error_message = NULL,
                lease_token = NULL, lease_until = NULL, updated_at = now()
            WHERE id = #{id} AND runtime_id = #{runtimeId}
              AND lease_token = #{leaseToken} AND status = 'RUNNING' AND delete_flag = FALSE
            """)
    int complete(@Param("id") Long id, @Param("runtimeId") Long runtimeId,
                 @Param("leaseToken") String leaseToken, @Param("sessionRef") String sessionRef,
                 @Param("outboundId") Long outboundId);

    @Update("""
            UPDATE agent_local_task
            SET status = #{status}, finished_at = now(), error_code = #{errorCode},
                error_message = #{errorMessage}, lease_token = NULL, lease_until = NULL,
                updated_at = now()
            WHERE id = #{id} AND runtime_id = #{runtimeId}
              AND status IN ('PENDING', 'CLAIMED', 'RUNNING') AND delete_flag = FALSE
            """)
    int fail(@Param("id") Long id, @Param("runtimeId") Long runtimeId,
             @Param("status") String status, @Param("errorCode") String errorCode,
             @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE agent_local_task
            SET last_seq = GREATEST(COALESCE(last_seq, 0), #{seq}), updated_at = now()
            WHERE id = #{id} AND runtime_id = #{runtimeId} AND delete_flag = FALSE
            """)
    int touchSeq(@Param("id") Long id, @Param("runtimeId") Long runtimeId,
                 @Param("seq") Long seq);

    @Update("""
            UPDATE agent_local_task
            SET status = 'EXPIRED', finished_at = now(),
                error_code = 'TIMEOUT', error_message = 'Task lease expired',
                lease_token = NULL, lease_until = NULL, updated_at = now()
            WHERE status IN ('CLAIMED', 'RUNNING')
              AND lease_until IS NOT NULL
              AND lease_until < #{before}
              AND delete_flag = FALSE
            """)
    int expireStale(@Param("before") LocalDateTime before);
}

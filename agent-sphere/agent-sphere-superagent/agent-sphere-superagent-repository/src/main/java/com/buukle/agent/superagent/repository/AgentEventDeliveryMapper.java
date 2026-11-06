package com.buukle.agent.superagent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentEventDeliveryMapper extends BaseMapper<AgentEventDelivery> {

    @Select("""
            SELECT * FROM agent_event_delivery
            WHERE status = 'PENDING'
              AND decision IN ('DIRECT', 'RELEVANT', 'PASS_THROUGH')
              AND (next_retry_at IS NULL OR next_retry_at <= now())
              AND delete_flag = FALSE
            ORDER BY id
            LIMIT #{limit}
            """)
    List<AgentEventDelivery> selectPending(@Param("limit") int limit);

    @Update("""
            UPDATE agent_event_delivery
            SET status = 'CLAIMED', claimed_at = now(), updated_at = now()
            WHERE id = #{id} AND status = 'PENDING' AND delete_flag = FALSE
            """)
    int claim(@Param("id") Long id);

    @Update("""
            UPDATE agent_event_delivery
            SET status = 'PENDING', attempts = attempts + 1,
                next_retry_at = #{nextRetryAt}, updated_at = now()
            WHERE id = #{id} AND status = 'CLAIMED' AND delete_flag = FALSE
            """)
    int release(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * Terminal-fails a PENDING delivery that can never be consumed (agent removed,
     * archived or not configured). Without this the row is picked up forever by
     * the fallback poll every cycle, starving the batch and spamming the log.
     */
    @Update("""
            UPDATE agent_event_delivery
            SET status = 'FAILED', remark = #{remark}, next_retry_at = NULL, updated_at = now()
            WHERE id = #{id} AND status = 'PENDING' AND delete_flag = FALSE
            """)
    int failPending(@Param("id") Long id, @Param("remark") String remark);
}

package com.buukle.agent.superagent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentOutboundMessageMapper extends BaseMapper<AgentOutboundMessage> {

    @Select("""
            SELECT * FROM agent_outbound_message
            WHERE status IN ('PENDING', 'RETRY')
              AND (next_retry_at IS NULL OR next_retry_at <= #{now})
              AND delete_flag = FALSE
            ORDER BY id
            LIMIT #{limit}
            """)
    List<AgentOutboundMessage> selectPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 进程在 SENDING 中途退出时，超时后重新排队，避免消息永久悬挂。 */
    /** Requeue rows whose send lease expired (process crashed or send hung). */
    @Update("""
            UPDATE agent_outbound_message
            SET status = 'RETRY', next_retry_at = now(), updated_at = now(),
                lease_token = NULL, lease_until = NULL
            WHERE status = 'SENDING'
              AND (lease_until IS NULL OR lease_until < now())
              AND delete_flag = FALSE
            """)
    int resetStuckSending();

    /** Claim one message with a unique lease token so only one replica may send it. */
    @Update("""
            UPDATE agent_outbound_message
            SET status = 'SENDING', lease_token = #{token}, lease_until = #{leaseUntil},
                updated_at = now()
            WHERE id = #{id}
              AND status IN ('PENDING', 'RETRY')
              AND (lease_until IS NULL OR lease_until < now())
              AND delete_flag = FALSE
            """)
    int claim(@Param("id") Long id,
              @Param("token") String token,
              @Param("leaseUntil") LocalDateTime leaseUntil);
}

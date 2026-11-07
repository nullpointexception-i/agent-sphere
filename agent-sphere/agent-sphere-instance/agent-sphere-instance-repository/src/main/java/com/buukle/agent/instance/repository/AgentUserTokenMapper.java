package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentUserToken;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentUserTokenMapper extends BaseMapper<AgentUserToken> {

    /**
     * Sliding renewal + last_used_at throttling: the database is written only when the last use is older than staleDays.
     *
     * <p>Call sites only hit this when the token cache misses and the DB must be consulted, which
     * inherently caps write amplification at one per token every 5 minutes at most; combined with
     * staleDays the effective write frequency is very low.
     */
    @Update("UPDATE agent_user_token SET "
            + "last_used_at = now(), "
            + "expires_at = now() + (#{ttlDays} * interval '1 day'), "
            + "updated_at = now() "
            + "WHERE token_hash = #{tokenHash} "
            + "AND revoked_at IS NULL "
            + "AND delete_flag = 0 "
            + "AND (last_used_at IS NULL OR last_used_at < now() - (#{staleDays} * interval '1 day'))")
    int touch(@Param("tokenHash") String tokenHash,
              @Param("ttlDays") int ttlDays,
              @Param("staleDays") int staleDays);

    /**
     * Physically delete expired/revoked session rows that are past their retention period.
     *
     * <p>A token is a temporary credential, not business data, so it must be physically deleted;
     * otherwise the table only grows and revoked historical credentials have no retention value.
     * This is in the same category as the audit log cleanup task and is not covered by
     * "never hard-delete business data".
     */
    @Delete("DELETE FROM agent_user_token WHERE "
            + "expires_at < now() - (#{retentionDays} * interval '1 day') "
            + "OR (revoked_at IS NOT NULL AND revoked_at < now() - (#{retentionDays} * interval '1 day'))")
    int deleteStale(@Param("retentionDays") int retentionDays);
}

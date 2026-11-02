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
     * 滑动续期 + last_used_at 节流：只有距上次使用超过 staleDays 才写一次库。
     *
     * <p>调用点只在「token 缓存未命中、需要回源 DB」时，天然把写放大压到每个 token
     * 每 5 分钟最多一次，再叠加 staleDays 后实际写频率极低。
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
     * 物理删除过期/已吊销且超过保留期的会话行。
     *
     * <p>token 是临时凭证而非业务数据，必须物理删除；否则表只增不减，且已吊销的
     * 历史凭证没有保留价值。这里与审计日志清理任务同类，不属于「业务数据禁止硬删」。
     */
    @Delete("DELETE FROM agent_user_token WHERE "
            + "expires_at < now() - (#{retentionDays} * interval '1 day') "
            + "OR (revoked_at IS NOT NULL AND revoked_at < now() - (#{retentionDays} * interval '1 day'))")
    int deleteStale(@Param("retentionDays") int retentionDays);
}

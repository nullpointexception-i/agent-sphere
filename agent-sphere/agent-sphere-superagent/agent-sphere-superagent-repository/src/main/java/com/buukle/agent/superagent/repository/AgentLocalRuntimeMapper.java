package com.buukle.agent.superagent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.superagent.domain.AgentLocalRuntime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentLocalRuntimeMapper extends BaseMapper<AgentLocalRuntime> {

    @Select("""
            SELECT * FROM agent_local_runtime
            WHERE token_hash = #{tokenHash}
              AND status = 'ACTIVE'
              AND delete_flag = FALSE
            LIMIT 1
            """)
    AgentLocalRuntime selectByTokenHash(@Param("tokenHash") String tokenHash);
}

package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.vo.RunUsageVO;
import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentLlmInteractionRecordMapper extends BaseMapper<AgentLlmInteractionRecord> {

    /** 批量 run 用量聚合（RunDrawer / timeline run_status 行用，避免逐 run 查询）。 */
    @Select("""
            <script>
            SELECT run_id                                              AS run_id,
                   COUNT(*)                                            AS interaction_count,
                   COALESCE(SUM(prompt_tokens), 0)                     AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0)                 AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)                      AS total_tokens,
                   COALESCE(SUM(cache_hit_tokens), 0)                  AS cache_hit_tokens,
                   COALESCE(SUM(cache_miss_tokens), 0)                 AS cache_miss_tokens
            FROM agent_llm_interaction_record
            WHERE delete_flag = 0 AND run_id IN
            <foreach item="rid" collection="runIds" open="(" separator="," close=")">#{rid}</foreach>
            GROUP BY run_id
            </script>
            """)
    List<RunUsageVO> sumUsageByRunIds(@Param("runIds") List<Long> runIds);

    /** 单 run 用量聚合（InteractionModal 头部 / 运营查询用）。 */
    @Select("""
            SELECT run_id                                              AS run_id,
                   COUNT(*)                                            AS interaction_count,
                   COALESCE(SUM(prompt_tokens), 0)                     AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0)                 AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)                      AS total_tokens,
                   COALESCE(SUM(cache_hit_tokens), 0)                  AS cache_hit_tokens,
                   COALESCE(SUM(cache_miss_tokens), 0)                 AS cache_miss_tokens
            FROM agent_llm_interaction_record
            WHERE delete_flag = 0 AND run_id = #{runId}
            GROUP BY run_id
            """)
    RunUsageVO getRunUsage(@Param("runId") Long runId);

    /** 会话级用量聚合（聊天区最下方吸底展示用）。 */
    @Select("""
            SELECT session_id                                          AS session_id,
                   COUNT(DISTINCT run_id)                              AS run_count,
                   COUNT(*)                                            AS interaction_count,
                   COALESCE(SUM(prompt_tokens), 0)                     AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0)                 AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)                      AS total_tokens,
                   COALESCE(SUM(cache_hit_tokens), 0)                  AS cache_hit_tokens,
                   COALESCE(SUM(cache_miss_tokens), 0)                 AS cache_miss_tokens
            FROM agent_llm_interaction_record
            WHERE delete_flag = 0 AND session_id = #{sessionId}
            GROUP BY session_id
            """)
    com.buukle.agent.instance.domain.vo.SessionUsageVO sumUsageBySession(@Param("sessionId") Long sessionId);
}
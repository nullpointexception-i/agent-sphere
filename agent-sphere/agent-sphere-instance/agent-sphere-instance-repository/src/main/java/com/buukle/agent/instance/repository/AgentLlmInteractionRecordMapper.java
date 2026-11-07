package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentLlmInteractionRecord;
import com.buukle.agent.instance.domain.vo.RunUsagePointVO;
import com.buukle.agent.instance.domain.vo.RunUsageVO;
import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import com.buukle.agent.instance.domain.vo.SuperAgentUsageVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentLlmInteractionRecordMapper extends BaseMapper<AgentLlmInteractionRecord> {

    /** Batch run usage aggregation (for RunDrawer / timeline run_status rows; avoids one query per run). */
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

    /** Single-run usage aggregation (InteractionModal header / operations queries). */
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

    /** Session-level usage aggregation (for the bottom-docked display in the chat area). */
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

    /** Token metrics of the most recent N LLM interactions (including sub-agents; the main-chat mode filters CHAT_REPLY on the frontend). */
    @Select("""
            SELECT id, interaction_type, model_name, created_at,
                   prompt_tokens, completion_tokens, total_tokens,
                   cache_hit_tokens, cache_miss_tokens
            FROM (
              SELECT id, interaction_type, model_name, created_at,
                     prompt_tokens, completion_tokens, total_tokens,
                     cache_hit_tokens, cache_miss_tokens
              FROM agent_llm_interaction_record
              WHERE run_id = #{runId}
                AND session_id = #{sessionId}
                AND delete_flag = 0
                AND id > #{afterId}
              ORDER BY id DESC
              LIMIT #{limit}
            ) AS usage_window
            ORDER BY id ASC
            """)
    List<RunUsagePointVO> selectUsageSeries(@Param("runId") Long runId,
                                            @Param("sessionId") Long sessionId,
                                            @Param("afterId") long afterId,
                                            @Param("limit") int limit);

    /** Prompt tokens of the session's latest main Agent interaction of the given type (current context usage). */
    @Select("""
            SELECT prompt_tokens
            FROM agent_llm_interaction_record
            WHERE session_id = #{sessionId}
              AND interaction_type = #{interactionType}
              AND sub_agent_run_id IS NULL
              AND delete_flag = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    Long selectLatestPromptTokens(@Param("sessionId") Long sessionId,
                                  @Param("interactionType") String interactionType);

    /** Daily usage of one Super-Agent grouped by session type (cost attribution view). */
    @Select("""
            SELECT super_agent_id,
                   TO_CHAR(created_at, 'YYYY-MM-DD')                   AS day,
                   session_type,
                   COUNT(DISTINCT run_id)                              AS run_count,
                   COUNT(*)                                            AS interaction_count,
                   COALESCE(SUM(prompt_tokens), 0)                     AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0)                 AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)                      AS total_tokens,
                   COALESCE(SUM(cache_hit_tokens), 0)                  AS cache_hit_tokens,
                   COALESCE(SUM(cache_miss_tokens), 0)                 AS cache_miss_tokens
            FROM agent_llm_interaction_record
            WHERE delete_flag = 0
              AND super_agent_id = #{superAgentId}
              AND created_at >= #{since}
            GROUP BY super_agent_id, TO_CHAR(created_at, 'YYYY-MM-DD'), session_type
            ORDER BY day DESC, session_type
            """)
    List<SuperAgentUsageVO> sumUsageBySuperAgent(@Param("superAgentId") Long superAgentId,
                                                 @Param("since") LocalDateTime since);
}

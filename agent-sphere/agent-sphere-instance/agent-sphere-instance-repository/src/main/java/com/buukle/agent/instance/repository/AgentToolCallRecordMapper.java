package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buukle.agent.instance.domain.AgentToolCallRecord;
import com.buukle.agent.instance.domain.vo.RunActivityVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentToolCallRecordMapper extends BaseMapper<AgentToolCallRecord> {

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM agent_llm_interaction_record WHERE run_id = #{runId} AND delete_flag = 0)
              + (SELECT COUNT(*) FROM agent_tool_call_record WHERE run_id = #{runId} AND session_id = #{sessionId} AND delete_flag = 0)
            AS total
            """)
    int countActivitiesByRun(@Param("runId") Long runId, @Param("sessionId") Long sessionId);

    @Select("""
            SELECT id, activity_type, created_at, session_id,
                   interaction_type, model_name, request_body, response_body, reasoning, reply_content, http_status,
                   duration_ms, llm_error_message, success,
                   step_id, tool_name, display_name_cn, display_name_en,
                   arguments_json, artifact, tool_status, tool_error_message
            FROM (
              SELECT id, 'llm_interaction' AS activity_type, created_at, session_id,
                     interaction_type, model_name, request_body, response_body, reasoning, reply_content, http_status,
                     duration_ms, error_message AS llm_error_message, success,
                     NULL AS step_id, NULL AS tool_name, NULL AS display_name_cn, NULL AS display_name_en,
                     NULL AS arguments_json, NULL AS artifact, NULL AS tool_status, NULL AS tool_error_message
              FROM agent_llm_interaction_record
              WHERE run_id = #{runId} AND delete_flag = 0
              UNION ALL
              SELECT id, 'tool_call', created_at, session_id,
                     NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                     NULL, NULL, NULL,
                     step_id, tool_name, display_name_cn, display_name_en, arguments_json, artifact, status, error_message
              FROM agent_tool_call_record
              WHERE run_id = #{runId} AND session_id = #{sessionId} AND delete_flag = 0
            ) AS activities
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<RunActivityVO> selectActivitiesByRun(@Param("runId") Long runId,
                                              @Param("sessionId") Long sessionId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);
}

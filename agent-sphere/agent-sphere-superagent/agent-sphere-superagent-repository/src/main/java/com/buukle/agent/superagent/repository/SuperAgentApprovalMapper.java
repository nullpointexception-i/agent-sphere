package com.buukle.agent.superagent.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only projection over the instance-owned approval/session tables.
 *
 * <p>The super-agent module owns the authorization rule (avatar owner), so the
 * join is kept here as a read model instead of leaking approval rows through
 * the instance API.
 */
@Mapper
public interface SuperAgentApprovalMapper {

    @Select("""
            SELECT a.id, a.approval_id, a.session_id, a.run_id, a.tool_calls_json, a.status,
                   a.created_at,
                   s.session_type, s.super_agent_id, s.work_item_id, s.title AS session_title,
                   g.name AS agent_name, g.owner_user_id
            FROM agent_tool_approval a
            JOIN agent_session s ON s.id = a.session_id AND s.delete_flag = 0
            LEFT JOIN agent_super_agent g ON g.id = s.super_agent_id AND g.delete_flag = false
            WHERE a.status = 'PENDING'
              AND a.delete_flag = 0
              AND (g.owner_user_id = #{userId}
                   OR (g.id IS NULL AND s.created_by = #{username}))
            ORDER BY a.created_at DESC, a.id DESC
            """)
    List<PendingApprovalRow> listPendingForOwner(@Param("userId") Long userId,
                                                 @Param("username") String username);

    @Select("""
            SELECT a.id, a.approval_id, a.session_id, a.run_id, a.tool_calls_json, a.status,
                   a.created_at,
                   s.session_type, s.super_agent_id, s.work_item_id, s.title AS session_title,
                   g.name AS agent_name, g.owner_user_id
            FROM agent_tool_approval a
            JOIN agent_session s ON s.id = a.session_id AND s.delete_flag = 0
            LEFT JOIN agent_super_agent g ON g.id = s.super_agent_id AND g.delete_flag = false
            WHERE a.status = 'PENDING'
              AND a.delete_flag = 0
            ORDER BY a.created_at DESC, a.id DESC
            """)
    List<PendingApprovalRow> listPendingAll();

    @Select("""
            SELECT a.id, a.approval_id, a.session_id, a.run_id, a.tool_calls_json, a.status,
                   a.created_at,
                   s.session_type, s.super_agent_id, s.work_item_id, s.title AS session_title,
                   g.name AS agent_name, g.owner_user_id
            FROM agent_tool_approval a
            JOIN agent_session s ON s.id = a.session_id AND s.delete_flag = 0
            LEFT JOIN agent_super_agent g ON g.id = s.super_agent_id AND g.delete_flag = false
            WHERE a.approval_id = #{approvalId}
              AND a.delete_flag = 0
            LIMIT 1
            """)
    PendingApprovalRow findByApprovalId(@Param("approvalId") String approvalId);
}

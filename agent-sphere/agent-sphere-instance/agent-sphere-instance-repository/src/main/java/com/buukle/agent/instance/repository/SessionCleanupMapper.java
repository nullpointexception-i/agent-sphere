package com.buukle.agent.instance.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Historical session cleanup SQL.
 *
 * <p>Deliberately does <b>not</b> extend {@code BaseMapper} and references no Entity: cleanup is
 * cross-domain (the tasks / completions / file_store tables each belong to other modules), and raw
 * SQL is the only way to operate on them without pulling in those module dependencies, which is why
 * this mapper can live inside the instance module.
 *
 * <p><b>Why everything is a hard delete (DELETE) instead of MyBatis-Plus remove():</b>
 * the session subtree contains large TEXT fields such as request/response/reasoning, and
 * {@code @TableLogic} would rewrite DELETE into {@code UPDATE ... SET delete_flag = 1}, leaving the
 * row data untouched in the heap table and <b>releasing no disk space</b>. This is the mapper's
 * explicit exemption from the "logical delete only" convention, used for cleanup only.
 *
 * <p><b>The delete order cannot be changed:</b> the whole database has only two real foreign keys
 * and neither has ON DELETE CASCADE — {@code agent_run.session_id → agent_session} and
 * {@code agent_task_artifact.task_id → agent_task}. PG defaults to NO ACTION, so a wrong order
 * fails outright with violates foreign key constraint. Method order equals delete order.
 *
 * <p><b>No delete_flag condition:</b> logically deleted rows occupy disk too and must be cleaned up
 * as well.
 */
@Mapper
public interface SessionCleanupMapper {

    // ==================== Driving queries ====================

    /**
     * Expired session ids (batched in ascending id order).
     *
     * <p>Expiry uses both created_at <b>and</b> updated_at: checking created_at alone would delete
     * the whole tree of sessions "created long ago but still in use today".
     *
     * <p>The two NOT EXISTS clauses are the active guards:
     * <ul>
     *   <li>a run is PENDING/RUNNING — it is in flight, so deleting it would destroy an in-progress session;</li>
     *   <li>a task is QUEUED/RUNNING — Bole open-layer long-running tasks only update task/run rows
     *       and never the session row, so the updated_at condition above alone would still delete them by mistake.</li>
     * </ul>
     * Status values are passed in by the caller from RunEnum / TaskEnum (no literals in SQL).
     */
    @Select("""
            <script>
            SELECT s.id
            FROM agent_session s
            WHERE s.delete_flag = 0
              AND COALESCE(s.session_type, 'CONSOLE') NOT IN ('GROUP_ROUTER', 'ROUTER', 'WORKER')
              AND s.created_at &lt; #{cutoff}
              AND s.updated_at  &lt; #{cutoff}
              AND NOT EXISTS (
                    SELECT 1 FROM agent_run r
                    WHERE r.session_id = s.id AND r.status IN
                    <foreach item="st" collection="activeRunStatuses" open="(" separator="," close=")">#{st}</foreach>)
              AND NOT EXISTS (
                    SELECT 1 FROM agent_task t
                    WHERE t.session_id = s.id AND t.status IN
                    <foreach item="st" collection="activeTaskStatuses" open="(" separator="," close=")">#{st}</foreach>)
            ORDER BY s.id
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectExpiredSessionIds(@Param("cutoff") LocalDateTime cutoff,
                                        @Param("activeRunStatuses") Collection<String> activeRunStatuses,
                                        @Param("activeTaskStatuses") Collection<String> activeTaskStatuses,
                                        @Param("limit") int limit);

    /**
     * <b>Total</b> number of expired sessions: the denominator of the progress bar.
     *
     * <p>The WHERE conditions are identical to {@link #selectExpiredSessionIds} and must stay
     * identical — otherwise the denominator and the actual processed count disagree and the progress
     * bar either overshoots or never reaches 100%. It must be computed once at the start of the run
     * and stored on the execution record: during a dry-run nothing is deleted, so recomputing the
     * denominator live would drift with the completed count and the percentage would be meaningless.
     */
    @Select("""
            <script>
            SELECT count(*) FROM agent_session s
            WHERE s.delete_flag = 0
              AND COALESCE(s.session_type, 'CONSOLE') NOT IN ('GROUP_ROUTER', 'ROUTER', 'WORKER')
              AND s.created_at &lt; #{cutoff}
              AND s.updated_at  &lt; #{cutoff}
              AND NOT EXISTS (
                    SELECT 1 FROM agent_run r
                    WHERE r.session_id = s.id AND r.status IN
                    <foreach item="st" collection="activeRunStatuses" open="(" separator="," close=")">#{st}</foreach>)
              AND NOT EXISTS (
                    SELECT 1 FROM agent_task t
                    WHERE t.session_id = s.id AND t.status IN
                    <foreach item="st" collection="activeTaskStatuses" open="(" separator="," close=")">#{st}</foreach>)
            </script>
            """)
    long countExpiredSessions(@Param("cutoff") LocalDateTime cutoff,
                              @Param("activeRunStatuses") Collection<String> activeRunStatuses,
                              @Param("activeTaskStatuses") Collection<String> activeTaskStatuses);

    /**
     * Number of sessions past the time threshold but blocked by the active guard (for diagnostics).
     *
     * <p>A large value means the retention period is too aggressive for the business cadence and the
     * data is not shrinking; operators need to know what is blocking it.
     */
    @Select("""
            <script>
            SELECT count(*)
            FROM agent_session s
            WHERE s.delete_flag = 0
              AND COALESCE(s.session_type, 'CONSOLE') NOT IN ('GROUP_ROUTER', 'ROUTER', 'WORKER')
              AND s.created_at &lt; #{cutoff}
              AND s.updated_at  &lt; #{cutoff}
              AND ( EXISTS (SELECT 1 FROM agent_run r
                            WHERE r.session_id = s.id AND r.status IN
                            <foreach item="st" collection="activeRunStatuses" open="(" separator="," close=")">#{st}</foreach>)
                 OR EXISTS (SELECT 1 FROM agent_task t
                            WHERE t.session_id = s.id AND t.status IN
                            <foreach item="st" collection="activeTaskStatuses" open="(" separator="," close=")">#{st}</foreach>) )
            </script>
            """)
    long countActiveBlockedSessions(@Param("cutoff") LocalDateTime cutoff,
                                    @Param("activeRunStatuses") Collection<String> activeRunStatuses,
                                    @Param("activeTaskStatuses") Collection<String> activeTaskStatuses);

    /** Task matched by session_id (first path). */
    @Select("""
            <script>
            SELECT id FROM agent_task WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    List<Long> selectTaskIdsBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /**
     * Tasks referenced by this batch's run.task_id (second path).
     *
     * <p>{@code agent_task.session_id} is nullable and may disagree with
     * {@code agent_run.session_id} (AgentTaskServiceImpl creates the session before writing the
     * task, and the artifact's run_id is backfilled in yet another round). Deleting only by
     * session_id would leave dangling tasks and their artifacts.
     */
    @Select("""
            <script>
            SELECT DISTINCT task_id FROM agent_run
            WHERE task_id IS NOT NULL AND session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    List<Long> selectTaskIdsByRunSessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** dry-run only: per-table row counts a batch of sessions would cascade-delete (order matches the actual deletion). */
    @Select("""
            <script>
            SELECT 'agent_timeline' AS table_name, count(*) AS row_count FROM agent_timeline WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_llm_interaction_record', count(*) FROM agent_llm_interaction_record WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_tool_call_record', count(*) FROM agent_tool_call_record WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_sub_agent_run', count(*) FROM agent_sub_agent_run WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_pending_clarification', count(*) FROM agent_pending_clarification WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_session_todo', count(*) FROM agent_session_todo WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_compact_record', count(*) FROM agent_compact_record WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_user_in_loop_record', count(*) FROM agent_user_in_loop_record WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_memory', count(*) FROM agent_memory WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_document', count(*) FROM agent_document WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_run', count(*) FROM agent_run WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            UNION ALL SELECT 'agent_task_artifact', count(*) FROM agent_task_artifact
            <choose>
                <when test="taskIds != null and !taskIds.isEmpty()">WHERE task_id IN
                    <foreach item="tid" collection="taskIds" open="(" separator="," close=")">#{tid}</foreach>
                </when>
                <otherwise>WHERE FALSE</otherwise>
            </choose>
            UNION ALL SELECT 'agent_task', count(*) FROM agent_task
            <choose>
                <when test="taskIds != null and !taskIds.isEmpty()">WHERE id IN
                    <foreach item="tid" collection="taskIds" open="(" separator="," close=")">#{tid}</foreach>
                </when>
                <otherwise>WHERE FALSE</otherwise>
            </choose>
            UNION ALL SELECT 'agent_session', count(*) FROM agent_session WHERE id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    List<Map<String, Object>> countCascadeBySessionIds(@Param("sessionIds") Collection<Long> sessionIds,
                                                       @Param("taskIds") Collection<Long> taskIds);

    // ==================== Cascade deletes (order cannot change) ====================

    /** 1. timeline is a read-model; ref_* soft-references every table below, so delete it first for the cleanest state. */
    @Delete("""
            <script>
            DELETE FROM agent_timeline WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteTimelineBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** 2. LLM interaction records: the biggest disk consumer (full TEXT for request/response/reasoning). */
    @Delete("""
            <script>
            DELETE FROM agent_llm_interaction_record WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteLlmInteractionBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Delete("""
            <script>
            DELETE FROM agent_tool_call_record WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteToolCallBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Delete("""
            <script>
            DELETE FROM agent_sub_agent_run WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteSubAgentRunBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Delete("""
            <script>
            DELETE FROM agent_pending_clarification WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deletePendingClarificationBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Delete("""
            <script>
            DELETE FROM agent_session_todo WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteSessionTodoBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Delete("""
            <script>
            DELETE FROM agent_compact_record WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteCompactRecordBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Delete("""
            <script>
            DELETE FROM agent_user_in_loop_record WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteUserInLoopBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Delete("""
            <script>
            DELETE FROM agent_memory WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteMemoryBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Delete("""
            <script>
            DELETE FROM agent_document WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteDocumentBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** 11. Must come before agent_session: the only real foreign key pointing at a session. */
    @Delete("""
            <script>
            DELETE FROM agent_run WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteRunBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** 12. agent_task_artifact has no delete_flag column (hard delete only) and holds the real foreign key to task. */
    @Delete("""
            <script>
            DELETE FROM agent_task_artifact WHERE task_id IN
            <foreach item="tid" collection="taskIds" open="(" separator="," close=")">#{tid}</foreach>
            </script>
            """)
    int deleteTaskArtifactByTaskIds(@Param("taskIds") Collection<Long> taskIds);

    /** 13. task must come after artifact and after session (soft reference, no FK). */
    @Delete("""
            <script>
            DELETE FROM agent_task WHERE id IN
            <foreach item="tid" collection="taskIds" open="(" separator="," close=")">#{tid}</foreach>
            </script>
            """)
    int deleteTaskByIds(@Param("taskIds") Collection<Long> taskIds);

    /** 14. Root table last: at this point nothing references it any more. */
    @Delete("""
            <script>
            DELETE FROM agent_session WHERE id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteSessionByIds(@Param("sessionIds") Collection<Long> sessionIds);

    // ==================== Standalone/orphan cleanup (not tied to a session, cleaned by time) ====================

    /**
     * Orphan tasks: session_id is null or points to a session that no longer exists, and the task is
     * not active.
     *
     * <p>The active guard applies on the cascade path, and this standalone path must likewise exclude
     * QUEUED/RUNNING — otherwise there would be a hole where "the main path does not delete active
     * tasks but the orphan path does".
     */
    @Select("""
            <script>
            SELECT t.id FROM agent_task t
            WHERE (t.session_id IS NULL
                   OR NOT EXISTS (SELECT 1 FROM agent_session s WHERE s.id = t.session_id))
              AND t.created_at &lt; #{cutoff}
              AND t.status NOT IN
            <foreach item="st" collection="activeTaskStatuses" open="(" separator="," close=")">#{st}</foreach>
            ORDER BY t.id
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectOrphanTaskIds(@Param("cutoff") LocalDateTime cutoff,
                                   @Param("activeTaskStatuses") Collection<String> activeTaskStatuses,
                                   @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM agent_task_artifact WHERE task_id IN
            <foreach item="tid" collection="taskIds" open="(" separator="," close=")">#{tid}</foreach>
            </script>
            """)
    long countArtifactsByTaskIds(@Param("taskIds") Collection<Long> taskIds);

    /** Orphan LLM interactions: rows with a null session_id (idx_llm_interaction_created already exists). */
    @Select("""
            <script>
            SELECT id FROM agent_llm_interaction_record
            WHERE session_id IS NULL AND created_at &lt; #{cutoff}
            ORDER BY id
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectOrphanLlmInteractionIds(@Param("cutoff") LocalDateTime cutoff,
                                             @Param("limit") int limit);

    @Select("""
            <script>
            SELECT id FROM agent_memory
            WHERE session_id IS NULL AND created_at &lt; #{cutoff}
            ORDER BY id
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectOrphanMemoryIds(@Param("cutoff") LocalDateTime cutoff,
                                     @Param("limit") int limit);

    /** Expired screenshots/attachments (biz_key whitelist; BYTEA is by far the largest disk consumer). */
    @Select("""
            <script>
            SELECT id FROM agent_file_store
            WHERE created_at &lt; #{cutoff} AND biz_key IN
            <foreach item="bk" collection="bizKeys" open="(" separator="," close=")">#{bk}</foreach>
            ORDER BY id
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectExpiredFileIds(@Param("cutoff") LocalDateTime cutoff,
                                    @Param("bizKeys") Collection<String> bizKeys,
                                    @Param("limit") int limit);

    @Select("""
            <script>
            SELECT id FROM agent_completions_call
            WHERE created_at &lt; #{cutoff}
            ORDER BY id
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectExpiredCompletionsCallIds(@Param("cutoff") LocalDateTime cutoff,
                                               @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM agent_llm_interaction_record WHERE id IN
            <foreach item="rid" collection="ids" open="(" separator="," close=")">#{rid}</foreach>
            </script>
            """)
    int deleteLlmInteractionsByIds(@Param("ids") Collection<Long> ids);

    @Delete("""
            <script>
            DELETE FROM agent_memory WHERE id IN
            <foreach item="rid" collection="ids" open="(" separator="," close=")">#{rid}</foreach>
            </script>
            """)
    int deleteMemoriesByIds(@Param("ids") Collection<Long> ids);

    @Delete("""
            <script>
            DELETE FROM agent_file_store WHERE id IN
            <foreach item="rid" collection="ids" open="(" separator="," close=")">#{rid}</foreach>
            </script>
            """)
    int deleteFilesByIds(@Param("ids") Collection<Long> ids);

    @Delete("""
            <script>
            DELETE FROM agent_completions_call WHERE id IN
            <foreach item="rid" collection="ids" open="(" separator="," close=")">#{rid}</foreach>
            </script>
            """)
    int deleteCompletionsCallsByIds(@Param("ids") Collection<Long> ids);

    /** IM attachments use per-conversation biz keys ({@code im-attachment:{id}}). */
    @Select("""
            SELECT id FROM agent_file_store
            WHERE created_at < #{cutoff}
              AND biz_key LIKE CONCAT(#{prefix}, '%')
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Long> selectExpiredFileIdsByPrefix(@Param("cutoff") LocalDateTime cutoff,
                                            @Param("prefix") String prefix,
                                            @Param("limit") int limit);
}

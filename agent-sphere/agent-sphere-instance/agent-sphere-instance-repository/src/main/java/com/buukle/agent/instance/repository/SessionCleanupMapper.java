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
 * 历史会话清理 SQL。
 *
 * <p>刻意<b>不</b>继承 {@code BaseMapper}、也不引用任何 Entity：清理是跨域的
 * （tasks / completions / file_store 三张表分别属于其他模块），用裸 SQL 才能在不引入
 * 那些模块依赖的前提下操作它们，Mapper 因此可以待在 instance 模块内。
 *
 * <p><b>为什么全部是硬删（DELETE）而不是 MyBatis-Plus 的 remove()：</b>
 * session 子树里有 request/response/reasoning 等 TEXT 大字段，{@code @TableLogic} 会把 DELETE
 * 改写成 {@code UPDATE ... SET delete_flag = 1}，行数据原封不动留在堆表里，
 * <b>不会释放任何磁盘</b>。这是本 Mapper 对「只做逻辑删除」约定的显式豁免，仅用于清理场景。
 *
 * <p><b>删除顺序不可调换：</b>全库只有两条真外键且都没有 ON DELETE CASCADE ——
 * {@code agent_run.session_id → agent_session} 与 {@code agent_task_artifact.task_id → agent_task}，
 * PG 默认 NO ACTION，顺序错了会直接 violates foreign key constraint。方法顺序即删除顺序。
 *
 * <p><b>不加 delete_flag 条件：</b>已逻辑删除的行同样占磁盘，必须一并清掉。
 */
@Mapper
public interface SessionCleanupMapper {

    // ==================== 驱动查询 ====================

    /**
     * 过期会话 id（按 id 升序分批）。
     *
     * <p>过期判定用 created_at <b>和</b> updated_at 双条件：只判 created_at 会把「创建很久、
     * 今天仍在用」的会话整棵删掉。
     *
     * <p>两个 NOT EXISTS 是活跃守卫：
     * <ul>
     *   <li>run 处于 PENDING/RUNNING —— 正在跑，删了等于毁掉进行中的会话；</li>
     *   <li>task 处于 QUEUED/RUNNING —— Bole 开放层长任务只更新 task/run 行、不会更新
     *       session 行，因此光靠上面的 updated_at 条件仍会误删。</li>
     * </ul>
     * 状态值由调用方从 RunEnum / TaskEnum 传入（不在 SQL 里写字面量）。
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
     * 过期会话<b>总数</b>：进度条的分母。
     *
     * <p>WHERE 条件与 {@link #selectExpiredSessionIds} 完全一致，必须一致 ——
     * 否则分母和实际处理量对不上，进度条会虚高或到不了 100%。
     * 必须在执行开始时算一次并存进执行记录：dry-run 期间一行都不删，
     * 现场重算分母会随已完成数一起变，百分比必然失真。
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
     * 过了时间线、但因活跃守卫被挡住的会话数（诊断用）。
     *
     * <p>这个值偏大说明保留期相对业务节奏太激进、数据没降下来，运维需要知道是被什么挡住的。
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

    /** session_id 命中的 task（第一路）。 */
    @Select("""
            <script>
            SELECT id FROM agent_task WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    List<Long> selectTaskIdsBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /**
     * 被本批 run 的 task_id 指向的 task（第二路）。
     *
     * <p>{@code agent_task.session_id} 可空，且与 {@code agent_run.session_id} 可能不一致
     * （AgentTaskServiceImpl 先建 session 再写 task，artifact 的 run_id 又是另一轮回填的）。
     * 只按 session_id 删会留下悬挂的 task 及其 artifact。
     */
    @Select("""
            <script>
            SELECT DISTINCT task_id FROM agent_run
            WHERE task_id IS NOT NULL AND session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    List<Long> selectTaskIdsByRunSessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** dry-run 用：一批 session 将连带删除的各表行数（顺序与实际删除一致）。 */
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

    // ==================== 级联删除（顺序不可调换） ====================

    /** 1. timeline 是 read-model，ref_* 软引用下面所有表，先删最干净。 */
    @Delete("""
            <script>
            DELETE FROM agent_timeline WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteTimelineBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** 2. LLM 交互记录：磁盘占用第一大户（request/response/reasoning 全量 TEXT）。 */
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

    /** 11. 必须在 agent_session 之前：唯一指向 session 的真外键。 */
    @Delete("""
            <script>
            DELETE FROM agent_run WHERE session_id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteRunBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    /** 12. agent_task_artifact 无 delete_flag 列（只能硬删），且是 task 的真外键持有者。 */
    @Delete("""
            <script>
            DELETE FROM agent_task_artifact WHERE task_id IN
            <foreach item="tid" collection="taskIds" open="(" separator="," close=")">#{tid}</foreach>
            </script>
            """)
    int deleteTaskArtifactByTaskIds(@Param("taskIds") Collection<Long> taskIds);

    /** 13. task 必须在 artifact 之后、session 之后（软引用，无 FK）。 */
    @Delete("""
            <script>
            DELETE FROM agent_task WHERE id IN
            <foreach item="tid" collection="taskIds" open="(" separator="," close=")">#{tid}</foreach>
            </script>
            """)
    int deleteTaskByIds(@Param("taskIds") Collection<Long> taskIds);

    /** 14. 根表最后：此时已无任何 run 引用。 */
    @Delete("""
            <script>
            DELETE FROM agent_session WHERE id IN
            <foreach item="sid" collection="sessionIds" open="(" separator="," close=")">#{sid}</foreach>
            </script>
            """)
    int deleteSessionByIds(@Param("sessionIds") Collection<Long> sessionIds);

    // ==================== 独立/孤儿清理（不挂在 session 上，按时间清） ====================

    /**
     * 孤儿 task：session_id 为空或指向已不存在的 session，且非活跃状态。
     *
     * <p>活跃守卫在级联路径上生效，这条独立路径也必须同样排除 QUEUED/RUNNING，
     * 否则会出现「主路径不删活跃任务、孤儿路径却删掉」的漏洞。
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

    /** LLM 交互孤儿：session_id 为空的行（idx_llm_interaction_created 已建）。 */
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

    /** 过期截图/附件（biz_key 白名单，BYTEA 是磁盘占用绝对大头）。 */
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

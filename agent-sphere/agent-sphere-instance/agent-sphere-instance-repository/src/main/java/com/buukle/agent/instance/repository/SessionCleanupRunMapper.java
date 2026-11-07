package com.buukle.agent.instance.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.instance.domain.vo.SessionCleanupRunRowVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * Read/write access to session cleanup execution records ({@code agent_session_cleanup_run}).
 *
 * <p>Raw SQL is used deliberately instead of an Entity + BaseMapper:
 * <ul>
 *   <li>{@code table_stats} is jsonb; going through MyBatis-Plus either sends a {@code String} that
 *       PG rejects (column type mismatch), or depends on infrastructure's {@code JsonbTypeHandler} —
 *       and infrastructure already depends on instance-service, so the reverse reference would form a cycle;</li>
 *   <li>this table is only used by the cleanup task, has few fields, and does not need MP CRUD.</li>
 * </ul>
 * Writes use {@code CAST(#{x} AS jsonb)}, reads use {@code CAST(table_stats AS text)}.
 *
 * <p>The records themselves are cleaned up too ({@link #deleteOlderThan}), driven by
 * {@code session.cleanup-log-retention-days}.
 */
@Mapper
public interface SessionCleanupRunMapper {

    /**
     * Open a RUNNING record.
     *
     * <p>Uses {@code INSERT … RETURNING id} rather than generated keys: a single-parameter
     * {@code @Insert} cannot retrieve the auto-increment primary key.
     *
     * <p><b>Must be called before taking the cleanup lock</b> — the two "did nothing" cases,
     * "lock is held" and "kill switch off", must also leave a trace; otherwise nobody can explain
     * afterwards why the disk did not shrink last night.
     */
    @Select("""
            INSERT INTO agent_session_cleanup_run (trigger_type, dry_run, status, created_by, updated_by)
            VALUES (#{triggerType}, #{dryRun}, #{statusRunning}, #{createdBy}, #{createdBy})
            RETURNING id
            """)
    Long insertRunning(@Param("triggerType") String triggerType,
                       @Param("dryRun") boolean dryRun,
                       @Param("statusRunning") String statusRunning,
                       @Param("createdBy") String createdBy);

    /** Mark SUCCESS: backfill the parameters used, hit counts, elapsed time and per-table details. */
    @Update("""
            UPDATE agent_session_cleanup_run
            SET status = #{statusSuccess}, retention_days = #{retentionDays}, file_retention_days = #{fileRetentionDays},
                cutoff = #{cutoff}, file_cutoff = #{fileCutoff}, session_count = #{sessionCount},
                total_sessions = #{totalSessions},
                total_rows = #{totalRows}, skipped_active_sessions = #{skippedActiveSessions},
                batches = #{batches}, truncated = #{truncated}, elapsed_ms = #{elapsedMs},
                table_stats = CAST(#{tableStatsJson} AS jsonb),
                remark = #{remark},
                finished_at = NOW(), updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("statusSuccess") String statusSuccess,
                    @Param("retentionDays") int retentionDays,
                    @Param("fileRetentionDays") int fileRetentionDays,
                    @Param("cutoff") LocalDateTime cutoff,
                    @Param("fileCutoff") LocalDateTime fileCutoff,
                    @Param("sessionCount") int sessionCount,
                    @Param("totalSessions") int totalSessions,
                    @Param("totalRows") long totalRows,
                    @Param("skippedActiveSessions") long skippedActiveSessions,
                    @Param("batches") int batches,
                    @Param("truncated") boolean truncated,
                    @Param("elapsedMs") long elapsedMs,
                    @Param("tableStatsJson") String tableStatsJson,
                    @Param("remark") String remark,
                    @Param("updatedBy") String updatedBy);

    /**
     * Incremental progress: update once after each batch so the frontend progress bar can move.
     *
     * <p>Does not write {@code finished_at} and does not change {@code status} — the record is still
     * RUNNING. At most 50 times per round (cleanup-max-batches), so the overhead is negligible.
     */
    @Update("""
            UPDATE agent_session_cleanup_run
            SET total_sessions = #{totalSessions},
                session_count = #{sessionCount},
                total_rows = #{totalRows},
                batches = #{batches},
                table_stats = CAST(#{tableStatsJson} AS jsonb),
                elapsed_ms = #{elapsedMs},
                updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markProgress(@Param("id") Long id,
                     @Param("totalSessions") int totalSessions,
                     @Param("sessionCount") int sessionCount,
                     @Param("totalRows") long totalRows,
                     @Param("batches") int batches,
                     @Param("elapsedMs") long elapsedMs,
                     @Param("tableStatsJson") String tableStatsJson,
                     @Param("updatedBy") String updatedBy);

    /** For polling: fetch a single record (including the progress fields). */
    @Select("""
            <script>
            SELECT id, trigger_type, dry_run, status, retention_days, file_retention_days,
                   cutoff, file_cutoff, session_count, total_sessions, total_rows, skipped_active_sessions,
                   batches, truncated, elapsed_ms,
                   CAST(table_stats AS text) AS table_stats,
                   skip_reason, error_message, started_at, finished_at, created_by, remark,
                   (finished_at IS NULL AND started_at &lt; #{staleBefore}) AS stale
            FROM agent_session_cleanup_run
            WHERE id = #{id}
            </script>
            """)
    SessionCleanupRunRowVO getRun(@Param("id") Long id,
                                  @Param("staleBefore") LocalDateTime staleBefore);

    /** Mark SKIPPED: the lock was held or the kill switch was off, so nothing was deleted. */
    @Update("""
            UPDATE agent_session_cleanup_run
            SET status = #{statusSkipped}, skip_reason = #{skipReason},
                elapsed_ms = #{elapsedMs}, finished_at = NOW(),
                updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markSkipped(@Param("id") Long id,
                    @Param("statusSkipped") String statusSkipped,
                    @Param("skipReason") String skipReason,
                    @Param("elapsedMs") long elapsedMs,
                    @Param("updatedBy") String updatedBy);

    /** Mark FAILED: record the exception summary; the cleanup exception is still propagated and is not swallowed here. */
    @Update("""
            UPDATE agent_session_cleanup_run
            SET status = #{statusFailed}, error_message = #{errorMessage},
                elapsed_ms = #{elapsedMs}, finished_at = NOW(),
                updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("statusFailed") String statusFailed,
                   @Param("errorMessage") String errorMessage,
                   @Param("elapsedMs") long elapsedMs,
                   @Param("updatedBy") String updatedBy);

    /** Execution record paging (descending by start time by default). {@code staleBefore} decides when a record counts as "suspected interruption". */
    @Select("""
            <script>
            SELECT id, trigger_type, dry_run, status, retention_days, file_retention_days,
                   cutoff, file_cutoff, session_count, total_sessions, total_rows, skipped_active_sessions,
                   batches, truncated, elapsed_ms,
                   CAST(table_stats AS text) AS table_stats,
                   skip_reason, error_message, started_at, finished_at, created_by, remark,
                   (finished_at IS NULL AND started_at &lt; #{staleBefore}) AS stale
            FROM agent_session_cleanup_run
            <where>
                <if test="triggerType != null">AND trigger_type = #{triggerType}</if>
                <if test="status != null">AND status = #{status}</if>
                <if test="dryRun != null">AND dry_run = #{dryRun}</if>
            </where>
            ORDER BY started_at DESC, id DESC
            </script>
            """)
    IPage<SessionCleanupRunRowVO> pageRuns(Page<SessionCleanupRunRowVO> page,
                                           @Param("triggerType") String triggerType,
                                           @Param("status") String status,
                                           @Param("dryRun") Boolean dryRun,
                                           @Param("staleBefore") LocalDateTime staleBefore);

    /** Retention policy for the record table itself: delete expired records so the cleanup mechanism does not become new garbage. */
    @Delete("""
            DELETE FROM agent_session_cleanup_run
            WHERE created_at < #{cutoff} AND id <> #{keepId}
            """)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("keepId") Long keepId);

    /** dry-run only: count, do not delete. */
    @Select("""
            SELECT count(*) FROM agent_session_cleanup_run
            WHERE created_at < #{cutoff} AND id <> #{keepId}
            """)
    long countOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("keepId") Long keepId);
}
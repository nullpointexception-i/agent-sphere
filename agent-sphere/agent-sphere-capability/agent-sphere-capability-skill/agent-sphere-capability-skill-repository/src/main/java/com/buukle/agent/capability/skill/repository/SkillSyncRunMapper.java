package com.buukle.agent.capability.skill.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * Read/write of skill auto-sync execution records ({@code capability_skill_sync_run}).
 *
 * <p>Raw SQL rather than an Entity: {@code detail} is jsonb, and an MP entity would either bind it
 * as a String (rejected by PG) or depend on infrastructure's JsonbTypeHandler (and infrastructure
 * already depends on instance-service, so business modules must not reference it back). Writes use
 * {@code CAST(#{x} AS jsonb)}, reads use {@code CAST(detail AS text)}.
 *
 * <p>This table is also the only evidence that the task actually ran: the sweeper's logger is
 * suppressed by logback's {@code com.buukle.agent.capability=WARN} rule, so log.info never reaches disk.
 */
@Mapper
public interface SkillSyncRunMapper {

    /** Inserts a RUNNING row. The id is fetched back via {@code INSERT … RETURNING id}. */
    @Select("""
            INSERT INTO capability_skill_sync_run (trigger_type, status, created_by, updated_by)
            VALUES (#{triggerType}, #{statusRunning}, #{createdBy}, #{createdBy})
            RETURNING id
            """)
    Long insertRunning(@Param("triggerType") String triggerType,
                       @Param("statusRunning") String statusRunning,
                       @Param("createdBy") String createdBy);

    /** Marks SUCCESS and writes back this round's statistics and skip details. */
    @Update("""
            UPDATE capability_skill_sync_run
            SET status = #{statusSuccess}, scanned_count = #{scannedCount}, handled = #{handled},
                updated = #{updated}, skipped = #{skipped}, batch_size = #{batchSize},
                detail = CAST(#{detailJson} AS jsonb),
                elapsed_ms = #{elapsedMs}, finished_at = NOW(),
                updated_by = #{updatedBy}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("statusSuccess") String statusSuccess,
                    @Param("scannedCount") int scannedCount,
                    @Param("handled") int handled,
                    @Param("updated") int updated,
                    @Param("skipped") int skipped,
                    @Param("batchSize") Integer batchSize,
                    @Param("detailJson") String detailJson,
                    @Param("elapsedMs") long elapsedMs,
                    @Param("updatedBy") String updatedBy);

    /** Marks SKIPPED: nothing was done (lock busy / global switch off). */
    @Update("""
            UPDATE capability_skill_sync_run
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

    /** Marks FAILED; the exception is still rethrown afterwards as usual. */
    @Update("""
            UPDATE capability_skill_sync_run
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

    @Select("""
            <script>
            SELECT id, trigger_type, status, scanned_count, handled, updated, skipped, batch_size,
                   CAST(detail AS text) AS detail,
                   skip_reason, error_message, elapsed_ms, started_at, finished_at, created_by,
                   (finished_at IS NULL AND started_at &lt; #{staleBefore}) AS stale
            FROM capability_skill_sync_run
            WHERE id = #{id}
            </script>
            """)
    SkillSyncRunRowVO getRun(@Param("id") Long id,
                             @Param("staleBefore") LocalDateTime staleBefore);

    @Select("""
            <script>
            SELECT id, trigger_type, status, scanned_count, handled, updated, skipped, batch_size,
                   CAST(detail AS text) AS detail,
                   skip_reason, error_message, elapsed_ms, started_at, finished_at, created_by,
                   (finished_at IS NULL AND started_at &lt; #{staleBefore}) AS stale
            FROM capability_skill_sync_run
            <where>
                <if test="triggerType != null">AND trigger_type = #{triggerType}</if>
                <if test="status != null">AND status = #{status}</if>
            </where>
            ORDER BY started_at DESC, id DESC
            </script>
            """)
    IPage<SkillSyncRunRowVO> pageRuns(Page<SkillSyncRunRowVO> page,
                                      @Param("triggerType") String triggerType,
                                      @Param("status") String status,
                                      @Param("staleBefore") LocalDateTime staleBefore);

    /** Retention policy of the record table itself: excludes the row just written in this round. */
    @Delete("""
            DELETE FROM capability_skill_sync_run
            WHERE created_at < #{cutoff} AND id <> #{keepId}
            """)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("keepId") Long keepId);
}
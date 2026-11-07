package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.constant.FileStoreBizKeys;
import com.buukle.agent.common.eventbus.DistributedRuntimeConstants;
import com.buukle.agent.common.support.AbstractRecordedTask;
import com.buukle.agent.instance.domain.vo.SessionCleanupRunRowVO;
import com.buukle.agent.instance.dtvo.enums.RunEnum;
import com.buukle.agent.instance.dtvo.enums.SessionCleanupRunStatusEnum;
import com.buukle.agent.instance.dtvo.enums.SessionCleanupTriggerEnum;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupReportVO;
import com.buukle.agent.instance.dtvo.vo.SessionCleanupRunVO;
import com.buukle.agent.instance.repository.SessionCleanupMapper;
import com.buukle.agent.instance.repository.SessionCleanupRunMapper;
import com.buukle.agent.tasks.dtvo.enums.TaskEnum;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Periodic cleanup of historical session data: reclaim disk space.
 *
 * <p><b>Why it must hard-delete:</b> inside the session subtree,
 * {@code agent_llm_interaction_record}'s request/response/reasoning and
 * {@code agent_tool_call_record}'s arguments and artifacts are all full TEXT, and
 * {@code @TableLogic} merely rewrites DELETE into {@code UPDATE delete_flag=1}, leaving the row data
 * in the heap table and <b>freeing not a single byte of disk</b>. This is the explicit exemption from
 * the "logical delete only" convention, limited to this task.
 *
 * <p><b>Expiry check</b>: {@code agent_session.created_at < cutoff AND updated_at < cutoff}; the
 * double condition avoids deleting "created long ago but still in use today" sessions by mistake.
 * Two NOT EXISTS active guards are added on top so that sessions owning a PENDING/RUNNING run or a
 * QUEUED/RUNNING task are never touched.
 *
 * <p><b>AWAITING_USER is deliberately not counted as active</b>: a clarification waiting for a user
 * reply may never come back, and including it in the guard would keep abandoned sessions out of
 * cleanup forever, cancelling out the purpose of cleanup.
 *
 * <p><b>Why this lives in the instance module instead of infrastructure:</b> infrastructure already
 * depends on instance-service, so the reverse dependency would form a cycle; session is also an
 * entity of the instance domain. {@code AuditLogCleanupTask} stays in infrastructure — audit logs
 * belong to the infrastructure domain and do not need to move.
 *
 * <p>Scheduling parameters (cron/batch size) live in yml; retention days and the kill switch live in
 * the system config table under {@code config_group='session'}, so no redeploy is needed after a
 * change.
 */
@Slf4j
@Component
public class SessionCleanupTask extends AbstractRecordedTask<SessionCleanupReportVO, Boolean> {

    /** Multi-replica mutex: the backend runs replicas=2, and without the lock both replicas would delete at the same time. */
    private static final String CLEANUP_LOCK_KEY = "scheduler:session-cleanup";

    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int DEFAULT_FILE_RETENTION_DAYS = 7;

    /** Active runs: they are in flight, so deleting them would destroy an in-progress session.
     *  Includes {@code WAITING} (suspended on the wait tool, it resumes automatically when the time
     *  arrives — unlike AWAITING_USER "waiting for a person who may never reply", it is a genuinely
     *  in-flight state and must be guarded). */
    private static final List<String> ACTIVE_RUN_STATUSES =
            List.of(RunEnum.STATUS_PENDING, RunEnum.STATUS_RUNNING, RunEnum.STATUS_WAITING);

    /** Active tasks: Bole open-layer long-running tasks only update task/run rows and never the session row, so they need their own guard. */
    private static final List<String> ACTIVE_TASK_STATUSES =
            List.of(TaskEnum.STATUS_QUEUED, TaskEnum.STATUS_RUNNING);

    /** Buckets where session artifacts are stored (screenshots/attachments), cleaned independently by time. */
    private static final List<String> CLEANUP_BIZ_KEYS =
            List.of(FileStoreBizKeys.BROWSER_SCREENSHOT, FileStoreBizKeys.CHAT_ATTACHMENT);

    /**
     * A hard delete only returns space for reuse inside PG; the data files do not shrink
     * automatically. Returning it to the operating system requires a manual VACUUM FULL during
     * off-peak hours (ACCESS EXCLUSIVE lock, blocking writes while it runs). Hence this is only a
     * hint, never executed automatically.
     */
    private static final String VACUUM_HINT =
            "Space has been reclaimed for reuse inside the database; data files do not shrink automatically. "
                    + "To return it to the operating system, run this manually during off-peak hours: "
                    + "VACUUM (FULL, ANALYZE) agent_llm_interaction_record, agent_tool_call_record, agent_file_store; "
                    + "(requires an ACCESS EXCLUSIVE lock and blocks writes while it runs)";

    static final String SKIP_LOCK_REASON = "Another replica or cleanup run is already executing; skipped this round";
    static final String SKIP_DISABLED_REASON = "Cleanup switch session.cleanup-enabled is false; skipped this round";

    /** Default retention days for the record table; the effective value comes from session.cleanup-log-retention-days. */
    private static final int DEFAULT_LOG_RETENTION_DAYS = 90;

    /** Key representing "the execution record table itself" in the report, so the frontend can see at a glance how many rows this step touched. */
    private static final String RUN_RECORD_TABLE = "agent_session_cleanup_run";

    /** Parse type for table_stats (jsonb text) → Map. */
    private static final TypeReference<Map<String, Long>> MAP_TYPE_REF = new TypeReference<>() {
    };

    private final SessionCleanupMapper sessionCleanupMapper;
    private final SessionCleanupRunMapper sessionCleanupRunMapper;
    private final SystemConfigSpi systemConfigSpi;

    /**
     * Executor used for asynchronous execution (infrastructure's {@code runtimeAsyncExecutor},
     * virtual threads + context propagation).
     *
     * <p>It is injected by bean name rather than using {@code @Async}: {@code @Async} does not apply
     * to same-class self-invocation, and this class needs both asynchronous submission (HTTP/cron
     * entries) and synchronous execution (unit tests asserting delete order), so splitting into two
     * beans would only add indirection.
     */
    private final Executor cleanupExecutor;

    public SessionCleanupTask(SessionCleanupMapper sessionCleanupMapper,
                              SessionCleanupRunMapper sessionCleanupRunMapper,
                              SystemConfigSpi systemConfigSpi,
                              RedissonClient redissonClient,
                              @Qualifier("runtimeAsyncExecutor") Executor cleanupExecutor) {
        super(redissonClient);
        this.sessionCleanupMapper = sessionCleanupMapper;
        this.sessionCleanupRunMapper = sessionCleanupRunMapper;
        this.systemConfigSpi = systemConfigSpi;
        this.cleanupExecutor = cleanupExecutor;
    }

    @Value("${buukle.agent.session.cleanup-batch-size:200}")
    private int batchSize;

    @Value("${buukle.agent.session.cleanup-max-batches:50}")
    private int maxBatches;

    @Value("${buukle.agent.session.cleanup-batch-sleep-ms:200}")
    private int batchSleepMs;

    /** Scheduled entry point: runs during off-peak hours (04:30 by default). Submitted asynchronously like the manual path, leaving a single code path. */
    @Scheduled(cron = "${buukle.agent.session.cleanup-cron:0 30 4 * * ?}")
    public void scheduledCleanup() {
        submitCleanup(false, SessionCleanupTriggerEnum.TRIGGER_SCHEDULED);
    }

    /**
     * Execution record list query (the admin "execution records" drawer).
     *
     * @param dryRun null means no filtering by rehearsal/real deletion (both rehearsals and real runs are listed, distinguished by the type column)
     */
    public IPage<SessionCleanupRunVO> listRuns(String triggerType, String status, Boolean dryRun,
                                               long page, long size) {
        Page<SessionCleanupRunRowVO> rowPage = new Page<>(page, size);
        IPage<SessionCleanupRunRowVO> rows = sessionCleanupRunMapper.pageRuns(rowPage, triggerType, status,
                dryRun, staleBefore());
        Page<SessionCleanupRunVO> voPage = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        voPage.setRecords(rows.getRecords().stream().map(this::toRunVO).toList());
        return voPage;
    }

    /**
     * Submit one cleanup round asynchronously and <b>return the execution record immediately</b> (status=RUNNING).
     *
     * <p>The HTTP entry uses it instead of running synchronously: a round covers up to 200×50
     * sessions and can take minutes; making the request thread wait would exhaust the connection pool
     * and leave the frontend guessing from timeouts. Hence "submit and return + poll the execution record".
     *
     * <p>The record is inserted on the <b>request thread</b> (not a background thread) so the id handed
     * to the frontend is immediately queryable; the operator must also be captured before submission —
     * recording a manual run as system would lose the clue of "who triggered it".
     *
     * @return the execution record in its initial state; the frontend polls {@link #getRun(Long)} with its id
     */
    public SessionCleanupRunVO submitCleanup(boolean dryRun, String trigger) {
        String operator = currentOperator();
        currentTrigger = trigger;
        Long recordId;
        try {
            recordId = insertRunning(dryRun, operator);
        } finally {
            currentTrigger = null;
        }
        cleanupExecutor.execute(() -> runWithLock(dryRun, recordId, operator, System.currentTimeMillis()));
        SessionCleanupRunRowVO row = sessionCleanupRunMapper.getRun(recordId, staleBefore());
        return row == null ? null : toRunVO(row);
    }

    /** Poll a single execution record (with live progress). */
    public SessionCleanupRunVO getRun(Long id) {
        SessionCleanupRunRowVO row = sessionCleanupRunMapper.getRun(id, staleBefore());
        return row == null ? null : toRunVO(row);
    }

    /**
     * Run one cleanup round synchronously (used by tests and internal synchronous calls).
     *
     * <p>The HTTP and cron entries both submit asynchronously via {@link #submitCleanup}; the
     * synchronous semantics are kept here so unit tests can assert the delete order and terminal
     * record state directly without waiting on a virtual thread.
     */
    public SessionCleanupReportVO runCleanup(boolean dryRun, String trigger) {
        String operator = currentOperator();
        currentTrigger = trigger;
        Long recordId;
        try {
            recordId = insertRunning(dryRun, operator);
        } finally {
            currentTrigger = null;
        }
        return runWithLock(dryRun, recordId, operator, System.currentTimeMillis());
    }

    /**
     * The trigger needed by insertRunning can only come from the entry parameter, and the base class
     * signature does not have it. Bridging through a field is safe here: insertRunning is always
     * called synchronously on the entry thread (see the two try/finally blocks), and the truly
     * cross-thread {@code runWithLock} never reads this field.
     */
    private String currentTrigger;

    /**
     * The actual body: acquire the lock → clean up → write the terminal state; a FAILED record is
     * written before any exception is propagated.
     *
     * <p>The record comes before the lock: failing to acquire the lock is also "something that
     * happened" and must be traceable.
     */
    @Override
    protected SessionCleanupReportVO doRun(Boolean dryRun, Long recordId, String operator, long startedAtMillis) {
        SessionCleanupReportVO report = new SessionCleanupReportVO();
        report.setDryRun(dryRun);

        if (!isCleanupEnabled()) {
            report.setEnabled(false);
            report.setSkipReason(SKIP_DISABLED_REASON);
            markSkipped(recordId, SKIP_DISABLED_REASON, System.currentTimeMillis() - startedAtMillis, operator);
            return report;
        }
        report.setEnabled(true);
        {

            int retentionDays = resolveRetentionDays();
            int fileRetentionDays = resolveFileRetentionDays();
            int logRetentionDays = resolveLogRetentionDays();
            BatchBudget budget = new BatchBudget(Math.max(maxBatches, 1));
            int sizeLimit = Math.max(batchSize, 1);

            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            LocalDateTime fileCutoff = LocalDateTime.now().minusDays(fileRetentionDays);
            report.setRetentionDays(retentionDays);
            report.setFileRetentionDays(fileRetentionDays);
            report.setCutoff(cutoff);
            report.setFileCutoff(fileCutoff);
            report.setSkippedActiveSessions(sessionCleanupMapper.countActiveBlockedSessions(
                    cutoff, ACTIVE_RUN_STATUSES, ACTIVE_TASK_STATUSES));
            // Progress denominator: computed once and fixed (nothing is deleted during a dry-run, so recomputing live would inevitably drift)
            report.setTotalSessions((int) sessionCleanupMapper.countExpiredSessions(
                    cutoff, ACTIVE_RUN_STATUSES, ACTIVE_TASK_STATUSES));

            cleanupExpiredSessions(cutoff, dryRun, sizeLimit, budget, report, recordId, operator,
                    startedAtMillis);
            cleanupIndependent(cutoff, fileCutoff, dryRun, sizeLimit, budget, report);
            cleanupOldRunRecords(logRetentionDays, dryRun, report, recordId);
            report.setTruncated(budget.truncated);
            report.setElapsedMs(System.currentTimeMillis() - startedAtMillis);
            report.setVacuumHint(dryRun ? null : VACUUM_HINT);
            logSummary(report);
            markSuccess(recordId, report, operator);
            return report;
        }
    }

    /**
     * Retention policy for the record table itself.
     *
     * <p>Placed <b>last</b> and excluding the record just written by this round ({@code id <> keepId}):
     * this table is very small (at most one automatic record per day), so a single full delete is
     * enough and it does not need batching like the business tables.
     */
    private void cleanupOldRunRecords(int logRetentionDays, boolean dryRun,
                                      SessionCleanupReportVO report, Long currentRecordId) {
        LocalDateTime logCutoff = LocalDateTime.now().minusDays(logRetentionDays);
        if (dryRun) {
            addStat(report, RUN_RECORD_TABLE,
                    sessionCleanupRunMapper.countOlderThan(logCutoff, currentRecordId));
        } else {
            addStat(report, RUN_RECORD_TABLE,
                    sessionCleanupRunMapper.deleteOlderThan(logCutoff, currentRecordId));
        }
    }

    /**
     * Cascade delete of expired sessions.
     *
     * <p>The strategy is "first select the target id set from the single {@code agent_session} table,
     * then cascade-delete child tables by session_id", rather than running a time filter on every
     * child table: the child tables all have session_id indexes (V74 added the missing ones for the
     * driving table and agent_task), while per-table filtering would miss data such as "a very old
     * session whose child rows were written recently".
     */
    private void cleanupExpiredSessions(LocalDateTime cutoff, boolean dryRun, int sizeLimit,
                                        BatchBudget budget, SessionCleanupReportVO report,
                                        Long recordId, String operator, long startedAtMillis) {
        while (budget.hasNext()) {
            List<Long> sessionIds = sessionCleanupMapper.selectExpiredSessionIds(
                    cutoff, ACTIVE_RUN_STATUSES, ACTIVE_TASK_STATUSES, sizeLimit);
            if (sessionIds.isEmpty()) {
                // Empty result = this category is completely cleaned up; there is no "more data left"
                return;
            }
            budget.consume();
            // batches must be accumulated here: it is the consumption of the "per-round batch budget"
            // and the actual batch count shown to operators. Missing it would make the batch count in
            // reports/records cover only the later standalone cleanup categories.
            report.setBatches(report.getBatches() + 1);
            report.setSessionCount(report.getSessionCount() + sessionIds.size());

            List<Long> taskIds = collectTaskIds(sessionIds);
            if (dryRun) {
                accumulateCounts(sessionIds, taskIds, report);
            } else {
                deleteCascade(sessionIds, taskIds, report);
                deleteTimelineSeqKeys(sessionIds);
                sleepBetweenBatches();
            }
            // Sync progress once per batch: this is what keeps the frontend progress bar moving (at most max-batches times)
            markProgress(recordId, report, operator, startedAtMillis);
        }
        // Exited because the batch budget ran out; there is very likely more data left
        budget.markTruncated();
    }

    /**
     * Standalone/orphan data not tied to a session: orphan tasks, orphan rows with a null session_id,
     * screenshots/attachments, and completions logs. Shares the same batch budget as the cascade path
     * (max-batches is a "per-round cap", not a "per-category cap").
     */
    private void cleanupIndependent(LocalDateTime cutoff, LocalDateTime fileCutoff, boolean dryRun,
                                    int sizeLimit, BatchBudget budget, SessionCleanupReportVO report) {
        // Orphan tasks: artifact holds the real foreign key to task, so artifacts must be deleted before tasks
        drain(limit -> sessionCleanupMapper.selectOrphanTaskIds(cutoff, ACTIVE_TASK_STATUSES, limit),
                ids -> {
                    if (dryRun) {
                        addStat(report, "agent_task_artifact",
                                sessionCleanupMapper.countArtifactsByTaskIds(ids));
                        addStat(report, "agent_task", ids.size());
                    } else {
                        addStat(report, "agent_task_artifact",
                                sessionCleanupMapper.deleteTaskArtifactByTaskIds(ids));
                        addStat(report, "agent_task", sessionCleanupMapper.deleteTaskByIds(ids));
                    }
                }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectOrphanLlmInteractionIds(cutoff, limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_llm_interaction_record", ids.size());
            } else {
                addStat(report, "agent_llm_interaction_record",
                        sessionCleanupMapper.deleteLlmInteractionsByIds(ids));
            }
        }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectOrphanMemoryIds(cutoff, limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_memory", ids.size());
            } else {
                addStat(report, "agent_memory", sessionCleanupMapper.deleteMemoriesByIds(ids));
            }
        }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectExpiredFileIds(fileCutoff, CLEANUP_BIZ_KEYS, limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_file_store", ids.size());
            } else {
                addStat(report, "agent_file_store", sessionCleanupMapper.deleteFilesByIds(ids));
            }
        }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectExpiredFileIdsByPrefix(
                        fileCutoff, FileStoreBizKeys.IM_ATTACHMENT + ":", limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_file_store", ids.size());
            } else {
                addStat(report, "agent_file_store", sessionCleanupMapper.deleteFilesByIds(ids));
            }
        }, dryRun, sizeLimit, budget);

        drain(limit -> sessionCleanupMapper.selectExpiredCompletionsCallIds(cutoff, limit), ids -> {
            if (dryRun) {
                addStat(report, "agent_completions_call", ids.size());
            } else {
                addStat(report, "agent_completions_call", sessionCleanupMapper.deleteCompletionsCallsByIds(ids));
            }
        }, dryRun, sizeLimit, budget);
    }

    /** Repeatedly fetch and process a batch of ids until empty or the batch budget runs out. */
    private void drain(IdBatchLoader loader, Consumer<List<Long>> handler, boolean dryRun,
                       int sizeLimit, BatchBudget budget) {
        while (budget.hasNext()) {
            List<Long> ids = loader.load(sizeLimit);
            if (ids.isEmpty()) {
                return;
            }
            budget.consume();
            handler.accept(ids);
            if (!dryRun) {
                sleepBetweenBatches();
            }
        }
        budget.markTruncated();
    }

    @FunctionalInterface
    private interface IdBatchLoader {
        List<Long> load(int limit);
    }

    /** Merge two paths: tasks matched by session_id ∪ tasks referenced by this batch's run.task_id. */
    private List<Long> collectTaskIds(List<Long> sessionIds) {
        Set<Long> taskIds = new LinkedHashSet<>(sessionCleanupMapper.selectTaskIdsBySessionIds(sessionIds));
        taskIds.addAll(sessionCleanupMapper.selectTaskIdsByRunSessionIds(sessionIds));
        return new ArrayList<>(taskIds);
    }

    /** dry-run: a single UNION ALL query fetches the row counts of the 14 tables for this batch. */
    private void accumulateCounts(List<Long> sessionIds, List<Long> taskIds, SessionCleanupReportVO report) {
        for (Map<String, Object> row : sessionCleanupMapper.countCascadeBySessionIds(sessionIds, taskIds)) {
            Object name = row.get("table_name");
            Object count = row.get("row_count");
            if (name != null && count instanceof Number number) {
                addStat(report, String.valueOf(name), number.longValue());
            }
        }
    }

    /**
     * Cascade delete. <b>The order is the foreign-key contract and cannot be changed</b>:
     * {@code agent_run.session_id → agent_session} and {@code agent_task_artifact.task_id → agent_task}
     * are the only two real foreign keys in the database and neither has ON DELETE CASCADE, so a wrong
     * order fails outright with violates foreign key constraint.
     */
    private void deleteCascade(List<Long> sessionIds, List<Long> taskIds, SessionCleanupReportVO report) {
        addStat(report, "agent_timeline", sessionCleanupMapper.deleteTimelineBySessionIds(sessionIds));
        addStat(report, "agent_llm_interaction_record",
                sessionCleanupMapper.deleteLlmInteractionBySessionIds(sessionIds));
        addStat(report, "agent_tool_call_record", sessionCleanupMapper.deleteToolCallBySessionIds(sessionIds));
        addStat(report, "agent_sub_agent_run", sessionCleanupMapper.deleteSubAgentRunBySessionIds(sessionIds));
        addStat(report, "agent_pending_clarification",
                sessionCleanupMapper.deletePendingClarificationBySessionIds(sessionIds));
        addStat(report, "agent_session_todo", sessionCleanupMapper.deleteSessionTodoBySessionIds(sessionIds));
        addStat(report, "agent_compact_record", sessionCleanupMapper.deleteCompactRecordBySessionIds(sessionIds));
        addStat(report, "agent_user_in_loop_record", sessionCleanupMapper.deleteUserInLoopBySessionIds(sessionIds));
        addStat(report, "agent_memory", sessionCleanupMapper.deleteMemoryBySessionIds(sessionIds));
        addStat(report, "agent_document", sessionCleanupMapper.deleteDocumentBySessionIds(sessionIds));
        addStat(report, "agent_run", sessionCleanupMapper.deleteRunBySessionIds(sessionIds));
        if (!taskIds.isEmpty()) {
            addStat(report, "agent_task_artifact", sessionCleanupMapper.deleteTaskArtifactByTaskIds(taskIds));
            addStat(report, "agent_task", sessionCleanupMapper.deleteTaskByIds(taskIds));
        }
        addStat(report, "agent_session", sessionCleanupMapper.deleteSessionByIds(sessionIds));
    }

    /** The timeline sequence key has no TTL, so it must be deleted together with the session, otherwise it lingers forever. */
    private void deleteTimelineSeqKeys(List<Long> sessionIds) {
        for (Long sessionId : sessionIds) {
            redissonClient.getAtomicLong(DistributedRuntimeConstants.timelineSeqKey(sessionId)).delete();
        }
    }

    private void addStat(SessionCleanupReportVO report, String table, long rows) {
        report.getTableStats().merge(table, rows, Long::sum);
    }

    /** Yield to autovacuum between batches: otherwise the dead tuples produced by a few hundred MB of deletes keep piling up. */
    private void sleepBetweenBatches() {
        if (batchSleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(batchSleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Session cleanup was interrupted", e);
        }
    }

    private boolean isCleanupEnabled() {
        return parseBoolean(systemConfigSpi.get(SystemConfigKeys.SESSION_CLEANUP_ENABLED, "true"), true);
    }

    private int resolveRetentionDays() {
        return parsePositiveInt(systemConfigSpi.get(SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS,
                String.valueOf(DEFAULT_RETENTION_DAYS)), DEFAULT_RETENTION_DAYS,
                SystemConfigKeys.SESSION_CLEANUP_RETENTION_DAYS);
    }

    private int resolveFileRetentionDays() {
        return parsePositiveInt(systemConfigSpi.get(SystemConfigKeys.SESSION_FILE_RETENTION_DAYS,
                String.valueOf(DEFAULT_FILE_RETENTION_DAYS)), DEFAULT_FILE_RETENTION_DAYS,
                SystemConfigKeys.SESSION_FILE_RETENTION_DAYS);
    }

    private int resolveLogRetentionDays() {
        return parsePositiveInt(systemConfigSpi.get(SystemConfigKeys.SESSION_CLEANUP_LOG_RETENTION_DAYS,
                String.valueOf(DEFAULT_LOG_RETENTION_DAYS)), DEFAULT_LOG_RETENTION_DAYS,
                SystemConfigKeys.SESSION_CLEANUP_LOG_RETENTION_DAYS);
    }

    /** Mark SUCCESS: write this round's full parameters and results back to the record. */
    private void markSuccess(Long recordId, SessionCleanupReportVO report, String operator) {
        // VACUUM advice goes into remark (this table is machine-written and has no manual-remark scenario, so a dedicated migration for one display string is not worth it)
        String remark = !report.isDryRun() && totalRows(report) > 0 ? VACUUM_HINT : null;
        sessionCleanupRunMapper.markSuccess(recordId, SessionCleanupRunStatusEnum.STATUS_SUCCESS,
                report.getRetentionDays(), report.getFileRetentionDays(), report.getCutoff(), report.getFileCutoff(),
                report.getSessionCount(), report.getTotalSessions(), totalRows(report),
                report.getSkippedActiveSessions(), report.getBatches(), report.isTruncated(),
                report.getElapsedMs(), JsonUtils.toJson(report.getTableStats()), remark, operator);
    }

    /**
     * Sync progress batch by batch: status is not changed and finished_at is not written, so the
     * record is still RUNNING. At most {@code cleanup-max-batches} UPDATEs per round, negligible
     * overhead.
     */
    private void markProgress(Long recordId, SessionCleanupReportVO report,
                              String operator, long startedAtMillis) {
        if (recordId == null) {
            return;
        }
        sessionCleanupRunMapper.markProgress(recordId, report.getTotalSessions(), report.getSessionCount(),
                totalRows(report), report.getBatches(), System.currentTimeMillis() - startedAtMillis,
                JsonUtils.toJson(report.getTableStats()), operator);
    }

    /** Mark SKIPPED: the lock was held or the kill switch was off — even doing nothing must leave a trace. */
    @Override
    protected void markSkipped(Long recordId, String reason, long elapsedMillis, String operator) {
        sessionCleanupRunMapper.markSkipped(recordId, SessionCleanupRunStatusEnum.STATUS_SKIPPED,
                reason, elapsedMillis, operator);
    }

    @Override
    protected void markFailed(Long recordId, Exception e, long elapsedMillis, String operator) {
        sessionCleanupRunMapper.markFailed(recordId, SessionCleanupRunStatusEnum.STATUS_FAILED,
                abbreviate(e.getMessage()), elapsedMillis, operator);
    }

    /**
     * The lock is held by another replica/request: a report must still be returned (callers and the
     * API need to know it was "skipped" rather than getting null), so the base class's default null
     * return is overridden here.
     */
    @Override
    protected SessionCleanupReportVO onLockBusy(Boolean dryRun, Long recordId,
                                                String operator, long elapsedMillis) {
        markSkipped(recordId, SKIP_LOCK_REASON, elapsedMillis, operator);
        SessionCleanupReportVO report = new SessionCleanupReportVO();
        report.setDryRun(dryRun);
        report.setSkippedByLock(true);
        report.setSkipReason(SKIP_LOCK_REASON);
        return report;
    }

    @Override
    protected String lockKey() {
        return CLEANUP_LOCK_KEY;
    }

    @Override
    protected String taskName() {
        return "Session cleanup";
    }

    @Override
    protected Long insertRunning(Boolean dryRun, String operator) {
        return sessionCleanupRunMapper.insertRunning(currentTrigger, dryRun,
                SessionCleanupRunStatusEnum.STATUS_RUNNING, operator);
    }

    /** Row shape → API shape, parsing the jsonb text into the per-table row count Map along the way. */
    private SessionCleanupRunVO toRunVO(SessionCleanupRunRowVO row) {
        SessionCleanupRunVO vo = new SessionCleanupRunVO();
        vo.setId(row.getId());
        vo.setTriggerType(row.getTriggerType());
        vo.setDryRun(row.isDryRun());
        vo.setStatus(row.getStatus());
        vo.setRetentionDays(row.getRetentionDays());
        vo.setFileRetentionDays(row.getFileRetentionDays());
        vo.setCutoff(row.getCutoff());
        vo.setFileCutoff(row.getFileCutoff());
        vo.setSessionCount(row.getSessionCount());
        vo.setTotalSessions(row.getTotalSessions());
        vo.setTotalRows(row.getTotalRows());
        vo.setSkippedActiveSessions(row.getSkippedActiveSessions());
        vo.setBatches(row.getBatches());
        vo.setTruncated(row.getTruncated());
        vo.setElapsedMs(row.getElapsedMs());
        vo.setSkipReason(row.getSkipReason());
        vo.setErrorMessage(row.getErrorMessage());
        vo.setStartedAt(row.getStartedAt());
        vo.setFinishedAt(row.getFinishedAt());
        vo.setStale(row.getStale());
        vo.setCreatedBy(row.getCreatedBy());
        vo.setRemark(row.getRemark());
        if (row.getTableStats() != null && !row.getTableStats().isBlank()) {
            vo.setTableStats(JsonUtils.parse(row.getTableStats(), MAP_TYPE_REF));
        }
        return vo;
    }

    /** Fall back to the default and warn when the configuration is invalid (blank/non-numeric/non-positive), so a bad config neither stalls cleanup nor deletes everything. */
    private int parsePositiveInt(String raw, int fallback, String key) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
            log.warn("Session cleanup: {}={} is not positive; falling back to the default {}", key, raw, fallback);
        } catch (NumberFormatException e) {
            log.warn("Session cleanup: {}={} is not a valid integer; falling back to the default {}", key, raw, fallback);
        }
        return fallback;
    }

    private boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        log.warn("Session cleanup: switch value {} is not a valid boolean; falling back to the default {}", value, fallback);
        return fallback;
    }

    /** Sum of all per-table row counts in the report. */
    private long totalRows(SessionCleanupReportVO report) {
        return report.getTableStats().values().stream().mapToLong(Long::longValue).sum();
    }

    /** Log at INFO only when something was actually deleted (aligned with AuditLogCleanupTask to avoid log spam every round); a dry-run is a manual request and is always logged. */
    private void logSummary(SessionCleanupReportVO report) {
        long total = report.getTableStats().values().stream().mapToLong(Long::longValue).sum();
        if (!report.isDryRun() && total == 0) {
            return;
        }
        log.info("Session cleanup {}: sessions={} activeBlocked={} batches={} truncated={} totalRows={} stats={} elapsed={}ms",
                report.isDryRun() ? "dry-run" : "done",
                report.getSessionCount(), report.getSkippedActiveSessions(), report.getBatches(),
                report.isTruncated(), total, report.getTableStats(), report.getElapsedMs());
        if (!report.isDryRun() && total > 0) {
            log.info("Session cleanup hint: {}", VACUUM_HINT);
        }
    }

    /**
     * Per-round batch budget: the cascade path and the standalone cleanup categories <b>share</b> the
     * same budget, ensuring a single run processes at most max-batches batches and does not run for a
     * very long time because there are many categories.
     *
     * <p>{@code truncated} means "there may still be unprocessed data": it is set only when the run
     * exits because the budget ran out. Returning an empty batch means the category is fully cleaned
     * and does not set it — otherwise "the last batch happened to be full" would be misreported as
     * not fully cleaned.
     */
    private static final class BatchBudget {

        private final int limit;
        private int used;
        private boolean truncated;

        private BatchBudget(int limit) {
            this.limit = limit;
        }

        private boolean hasNext() {
            return used < limit;
        }

        private void consume() {
            used++;
        }

        private void markTruncated() {
            truncated = true;
        }
    }
}

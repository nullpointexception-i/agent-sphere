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
 * 历史会话数据周期清理：释放磁盘。
 *
 * <p><b>为什么必须硬删：</b>session 子树里 {@code agent_llm_interaction_record} 的
 * request/response/reasoning 与 {@code agent_tool_call_record} 的参数、产物都是全量 TEXT，
 * {@code @TableLogic} 只把 DELETE 改写成 {@code UPDATE delete_flag=1}，行数据原样留在堆表里，
 * <b>一行磁盘都不会释放</b>。这是对「只做逻辑删除」约定的显式豁免，仅限本任务。
 *
 * <p><b>过期判定</b>：{@code agent_session.created_at < cutoff AND updated_at < cutoff}，
 * 双条件是为了不误删「创建很久、今天仍在用」的会话；再叠加两个 NOT EXISTS 活跃守卫，
 * 保证 PENDING/RUNNING 的 run 与 QUEUED/RUNNING 的 task 所在会话一定不会被碰。
 *
 * <p><b>刻意不把 AWAITING_USER 算作活跃</b>：等用户回复的澄清可能永远不回来，纳入守卫会让
 * 废弃会话永久挡在清理之外，正好抵消清理的意义。
 *
 * <p><b>为什么落在 instance 模块而不是 infrastructure：</b>infrastructure 已依赖
 * instance-service，反向依赖会成环；session 本身也是 instance 域的实体。
 * {@code AuditLogCleanupTask} 留在 infrastructure —— 审计日志属基础设施域，无需搬迁。
 *
 * <p>调度参数（cron/批大小）在 yml；保留天数与急停开关在系统配置表 {@code config_group='session'}，
 * 改完不必发版。
 */
@Slf4j
@Component
public class SessionCleanupTask extends AbstractRecordedTask<SessionCleanupReportVO, Boolean> {

    /** 多副本互斥锁：后端 replicas=2，不加锁两个副本会同时删。 */
    private static final String CLEANUP_LOCK_KEY = "scheduler:session-cleanup";

    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int DEFAULT_FILE_RETENTION_DAYS = 7;

    /** 活跃 run：正在跑，删了等于毁掉进行中的会话。
     *  含 {@code WAITING}（wait 挂起，到点会自动续跑 —— 与 AWAITING_USER「等人可能永不回」不同，
     *  它是真正在途的状态，必须守卫）。 */
    private static final List<String> ACTIVE_RUN_STATUSES =
            List.of(RunEnum.STATUS_PENDING, RunEnum.STATUS_RUNNING, RunEnum.STATUS_WAITING);

    /** 活跃 task：Bole 开放层长任务只更新 task/run 行、不更新 session 行，必须单独守卫。 */
    private static final List<String> ACTIVE_TASK_STATUSES =
            List.of(TaskEnum.STATUS_QUEUED, TaskEnum.STATUS_RUNNING);

    /** 会话产物落库的桶（截图/附件），按时间独立清理。 */
    private static final List<String> CLEANUP_BIZ_KEYS =
            List.of(FileStoreBizKeys.BROWSER_SCREENSHOT, FileStoreBizKeys.CHAT_ATTACHMENT);

    /**
     * 硬删只把空间还给 PG 内部复用，数据文件不会自动变小；要归还给操作系统必须低峰期手工
     * VACUUM FULL（ACCESS EXCLUSIVE 锁，期间阻塞写入）。故只提示、不自动执行。
     */
    private static final String VACUUM_HINT =
            "空间已回收至库内可复用，数据文件不会自动变小。如需归还磁盘请低峰期手工执行："
                    + "VACUUM (FULL, ANALYZE) agent_llm_interaction_record, agent_tool_call_record, agent_file_store; "
                    + "（需 ACCESS EXCLUSIVE 锁，期间阻塞写入）";

    private static final String SKIP_LOCK_REASON = "另一副本或另一次清理正在执行，本次跳过";
    private static final String SKIP_DISABLED_REASON = "清理开关 session.cleanup-enabled 为 false，本次跳过";

    /** 记录表保留天数默认值；实际值来自 session.cleanup-log-retention-days。 */
    private static final int DEFAULT_LOG_RETENTION_DAYS = 90;

    /** 报告里代表「执行记录表本身」的 key，便于前端一眼看出这一步动了多少行。 */
    private static final String RUN_RECORD_TABLE = "agent_session_cleanup_run";

    /** table_stats（jsonb 文本）→ Map 的解析类型。 */
    private static final TypeReference<Map<String, Long>> MAP_TYPE_REF = new TypeReference<>() {
    };

    private final SessionCleanupMapper sessionCleanupMapper;
    private final SessionCleanupRunMapper sessionCleanupRunMapper;
    private final SystemConfigSpi systemConfigSpi;

    /**
     * 异步执行用的执行器（infrastructure 的 {@code runtimeAsyncExecutor}，虚拟线程 + 上下文传播）。
     *
     * <p>这里按 bean 名注入而不是 {@code @Async}：{@code @Async} 在同类内部自调用不生效，
     * 而本类既要异步提交（HTTP/cron 入口）又要同步执行（单测断言删除顺序），拆两个 Bean 反而绕。
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

    /** 定时入口：低峰期执行（默认 04:30）。与手动一样异步提交，只留下一条代码路径。 */
    @Scheduled(cron = "${buukle.agent.session.cleanup-cron:0 30 4 * * ?}")
    public void scheduledCleanup() {
        submitCleanup(false, SessionCleanupTriggerEnum.TRIGGER_SCHEDULED);
    }

    /**
     * 执行记录的列表查询（管理端「执行记录」抽屉）。
     *
     * @param dryRun null 表示不按预演/实删过滤（预演与真实执行都列出来，靠类型列区分）
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
     * 异步提交一轮清理，**立即返回**执行记录（status=RUNNING）。
     *
     * <p>HTTP 入口用它而不是同步执行：一轮最多 200×50 个会话、耗时可达数分钟，
     * 让请求线程干等既拖垮连接池，前端也只能靠超时猜结果。改为「提交即返回 + 轮询执行记录」。
     *
     * <p>记录在<b>请求线程</b>上插入（不是后台线程），这样前端拿到的 id 立刻可查；
     * operator 也必须在提交前取 —— 手动执行记成 system 会丢掉「谁触发的」这条线索。
     *
     * @return 初始状态的执行记录，前端拿其中的 id 去轮询 {@link #getRun(Long)}
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

    /** 轮询单条执行记录（含实时进度）。 */
    public SessionCleanupRunVO getRun(Long id) {
        SessionCleanupRunRowVO row = sessionCleanupRunMapper.getRun(id, staleBefore());
        return row == null ? null : toRunVO(row);
    }

    /**
     * 同步执行一轮清理（测试与内部同步调用走它）。
     *
     * <p>HTTP 与 cron 入口都用 {@link #submitCleanup} 异步提交，这里保留同步语义是为了让
     * 单测能直接断言删除顺序与记录终态，不必去等虚拟线程。
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
     * insertRunning 需要的 trigger 只能从入口参数拿，而基类签名里没有它。
     * 这里用字段搭桥是安全的：insertRunning 一定在入口线程内同步调用完（见两处 try/finally），
     * 真正跨线程的 {@code runWithLock} 不再读这个字段。
     */
    private String currentTrigger;

    /**
     * 真正的执行体：抢锁 → 清理 → 落终态，异常上抛前先留 FAILED 记录。
     *
     * <p>记录先于锁：抢不到锁也是一种「发生过的事」，需要能查到。
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
            // 进度分母：算一次就固定下来（dry-run 期间一行不删，现场重算必然失真）
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
     * 记录表自身的保留策略。
     *
     * <p>放在<b>最后</b>，且排除本轮刚写的记录（{@code id <> keepId}）：这张表量很小
     * （每天至多一条自动记录），一次全量删即可，不需要像业务表那样分批。
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
     * 过期会话级联删除。
     *
     * <p>策略是「先用 {@code agent_session} 一张表选出目标 id 集合，再按 session_id 级联删子表」，
     * 而不是给每张子表各跑一次时间过滤：子表的 session_id 索引齐全（V74 补齐了驱动表与
     * agent_task），而逐表过滤会漏掉「会话很老、子行却是最近写的」这类数据。
     */
    private void cleanupExpiredSessions(LocalDateTime cutoff, boolean dryRun, int sizeLimit,
                                        BatchBudget budget, SessionCleanupReportVO report,
                                        Long recordId, String operator, long startedAtMillis) {
        while (budget.hasNext()) {
            List<Long> sessionIds = sessionCleanupMapper.selectExpiredSessionIds(
                    cutoff, ACTIVE_RUN_STATUSES, ACTIVE_TASK_STATUSES, sizeLimit);
            if (sessionIds.isEmpty()) {
                // 取空 = 这一类已彻底清完，不存在「还有数据」
                return;
            }
            budget.consume();
            // batches 要在这里累加：它是「单轮批次数预算」的消耗量，也是给运维看的实际批数。
            // 漏加会导致报告/记录里的批次数只统计到后面几个独立清理类别。
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
            // 每批同步一次进度：前端进度条靠这个动起来（最多 max-batches 次）
            markProgress(recordId, report, operator, startedAtMillis);
        }
        // 因批次预算耗尽而退出，后面很可能还有数据
        budget.markTruncated();
    }

    /**
     * 不挂在 session 上的独立/孤儿数据：孤儿 task、session_id 为空的孤儿行、截图附件、completions 日志。
     * 与级联路径共用同一个批次数预算（max-batches 是「单轮上限」而非「每个类别上限」）。
     */
    private void cleanupIndependent(LocalDateTime cutoff, LocalDateTime fileCutoff, boolean dryRun,
                                    int sizeLimit, BatchBudget budget, SessionCleanupReportVO report) {
        // 孤儿 task：artifact 是 task 的真外键，必须先删 artifact 再删 task
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

    /** 反复取一批 id 处理掉，直到取空或批次数预算耗尽。 */
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

    /** 两路合并：session_id 命中的 task ∪ 本批 run 的 task_id 指向的 task。 */
    private List<Long> collectTaskIds(List<Long> sessionIds) {
        Set<Long> taskIds = new LinkedHashSet<>(sessionCleanupMapper.selectTaskIdsBySessionIds(sessionIds));
        taskIds.addAll(sessionCleanupMapper.selectTaskIdsByRunSessionIds(sessionIds));
        return new ArrayList<>(taskIds);
    }

    /** dry-run：一条 UNION ALL 拿到这一批 14 张表的行数。 */
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
     * 级联删除。<b>顺序即外键约束，不可调换</b>：
     * {@code agent_run.session_id → agent_session} 与 {@code agent_task_artifact.task_id → agent_task}
     * 是全库仅有的两条真外键，且都没有 ON DELETE CASCADE，顺序错了会直接
     * violates foreign key constraint。
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

    /** timeline 序号键没有 TTL，删会话必须一并删，否则永久残留。 */
    private void deleteTimelineSeqKeys(List<Long> sessionIds) {
        for (Long sessionId : sessionIds) {
            redissonClient.getAtomicLong(DistributedRuntimeConstants.timelineSeqKey(sessionId)).delete();
        }
    }

    private void addStat(SessionCleanupReportVO report, String table, long rows) {
        report.getTableStats().merge(table, rows, Long::sum);
    }

    /** 批间让位给 autovacuum：否则一轮几百 MB 的删除产生的死元组会一直堆着。 */
    private void sleepBetweenBatches() {
        if (batchSleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(batchSleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("会话清理被中断", e);
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

    /** 落 SUCCESS：把本轮的完整口径与结果写回记录。 */
    private void markSuccess(Long recordId, SessionCleanupReportVO report, String operator) {
        // VACUUM 建议写进 remark（该表由机器写入、没有人工备注场景，不值得为一句展示文案再开一个迁移）
        String remark = !report.isDryRun() && totalRows(report) > 0 ? VACUUM_HINT : null;
        sessionCleanupRunMapper.markSuccess(recordId, SessionCleanupRunStatusEnum.STATUS_SUCCESS,
                report.getRetentionDays(), report.getFileRetentionDays(), report.getCutoff(), report.getFileCutoff(),
                report.getSessionCount(), report.getTotalSessions(), totalRows(report),
                report.getSkippedActiveSessions(), report.getBatches(), report.isTruncated(),
                report.getElapsedMs(), JsonUtils.toJson(report.getTableStats()), remark, operator);
    }

    /**
     * 逐批同步进度：不改 status、不写 finished_at，记录仍是 RUNNING。
     * 单轮最多 {@code cleanup-max-batches} 次 UPDATE，开销可忽略。
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

    /** 落 SKIPPED：锁被占用或急停开关关闭 —— 什么都没删也要有痕迹。 */
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
     * 锁被别的副本/请求占用：仍要返回一份报告（调用方与接口需要知道「被跳过」而不是拿到 null），
     * 所以这里覆写基类的默认 null 返回。
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

    /** 行形态 → API 形态，顺带把 jsonb 文本解析成各表行数 Map。 */
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

    /** 配置非法（空串/非数字/非正数）时回退默认值并告警，不因配置写错而让清理停摆或删光数据。 */
    private int parsePositiveInt(String raw, int fallback, String key) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
            log.warn("Session cleanup: {}={} 不是正数，回退默认值 {}", key, raw, fallback);
        } catch (NumberFormatException e) {
            log.warn("Session cleanup: {}={} 不是合法整数，回退默认值 {}", key, raw, fallback);
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
        log.warn("Session cleanup: 开关值 {} 不是合法布尔值，回退默认值 {}", value, fallback);
        return fallback;
    }

    /** 报告里各表行数之和。 */
    private long totalRows(SessionCleanupReportVO report) {
        return report.getTableStats().values().stream().mapToLong(Long::longValue).sum();
    }

    /** 只在真有东西被删时才打 INFO（对齐 AuditLogCleanupTask，避免每轮刷屏）；dry-run 是人工请求，必打。 */
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
     * 单轮批次数预算：级联路径与各独立清理类别<b>共用</b>同一预算，
     * 保证一次运行最多处理 max-batches 批，不会因为类别多而失控跑很久。
     *
     * <p>{@code truncated} 的语义是「可能还有数据没处理」：只有因预算耗尽而退出才会置位；
     * 取空返回说明该类别已清完，不置位 —— 否则「最后一批刚好取满」会被误报成没清干净。
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

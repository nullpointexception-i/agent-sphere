package com.buukle.agent.capability.skill.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncOutcome;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncRunStatusEnum;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncTriggerEnum;
import com.buukle.agent.capability.skill.dtvo.vo.SkillSyncRunVO;
import com.buukle.agent.capability.skill.repository.SkillSyncRunMapper;
import com.buukle.agent.capability.skill.repository.SkillSyncRunRowVO;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.support.AbstractRecordedTask;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Execution body of Skill Hub auto-sync (shared by the scheduled and manual triggers).
 *
 * <p><b>Root cause of the "no effect" bug that was fixed</b>: the original
 * {@code SkillAutoUpdateSweeper} scan condition included {@code created_at >= now - 7 days}, but a
 * copy's created_at is its <b>install time</b> - copies installed more than 7 days ago were
 * <b>never scanned again</b>. The query now filters only on {@code auto_update=true AND
 * origin_skill_id IS NOT NULL}, so copies follow their origin no matter how old they are.
 * (The index {@code idx_skill_auto_update(auto_update, origin_skill_id)} covers it exactly.)
 *
 * <p><b>Why execution records are mandatory</b>: this task's logger is suppressed by logback's
 * {@code com.buukle.agent.capability=WARN} rule, and its only success log is log.info - whether the
 * task ran, how many copies were scanned and how many were synced can never be answered from the
 * logs alone. One row per round is the answer.
 *
 * <p>Locking and exception semantics are reused from {@link AbstractRecordedTask}, the same
 * skeleton as session cleanup.
 */
@Slf4j
@Component
public class SkillSyncRunner extends AbstractRecordedTask<SkillSyncRunVO, String> {

    private static final String LOCK_KEY = "scheduler:skill-auto-update";
    private static final int DEFAULT_LOG_RETENTION_DAYS = 30;
    /** Maximum number of skip details to record, so detail does not become a huge JSON blob when every origin is offline */
    private static final int MAX_DETAIL_ITEMS = 100;
    private static final String TASK_NAME = "Skill auto-update sync";

    private final CapabilitySkillService capabilitySkillService;
    private final SkillSyncRunMapper skillSyncRunMapper;
    private final SystemConfigSpi systemConfigSpi;
    private final Executor syncExecutor;

    public SkillSyncRunner(CapabilitySkillService capabilitySkillService,
                           SkillSyncRunMapper skillSyncRunMapper,
                           SystemConfigSpi systemConfigSpi,
                           RedissonClient redissonClient,
                           @Qualifier("runtimeAsyncExecutor") Executor syncExecutor) {
        super(redissonClient);
        this.capabilitySkillService = capabilitySkillService;
        this.skillSyncRunMapper = skillSyncRunMapper;
        this.systemConfigSpi = systemConfigSpi;
        this.syncExecutor = syncExecutor;
    }

    @Value("${buukle.agent.skill.auto-update-batch-size:100}")
    private int batchSize;

    // ==================== Entry points ====================

    /** Scheduled entry point: fixed delay of 5 minutes (the original interval is preserved). */
    @Scheduled(fixedDelayString = "${buukle.agent.skill.auto-update-interval:PT5M}")
    public void scheduledSync() {
        submitSync(SkillSyncTriggerEnum.TRIGGER_SCHEDULED);
    }

    /** Submits one round asynchronously and returns the initial record immediately; the frontend polls {@link #getRun(Long)} with the id. */
    public SkillSyncRunVO submitSync(String trigger) {
        String operator = currentOperator();
        Long recordId = insertRunning(trigger, operator);
        syncExecutor.execute(() -> runWithLock(trigger, recordId, operator, System.currentTimeMillis()));
        SkillSyncRunRowVO row = skillSyncRunMapper.getRun(recordId, staleBefore());
        return row == null ? null : toRunVO(row);
    }

    /** Runs one round synchronously (used by unit tests, which can then assert on the scan condition and terminal record state). */
    public SkillSyncRunVO runSync(String trigger) {
        String operator = currentOperator();
        Long recordId = insertRunning(trigger, operator);
        return runWithLock(trigger, recordId, operator, System.currentTimeMillis());
    }

    public SkillSyncRunVO getRun(Long id) {
        SkillSyncRunRowVO row = skillSyncRunMapper.getRun(id, staleBefore());
        return row == null ? null : toRunVO(row);
    }

    public IPage<SkillSyncRunVO> listRuns(String triggerType, String status, long page, long size) {
        Page<SkillSyncRunRowVO> rowPage = new Page<>(page, size);
        IPage<SkillSyncRunRowVO> rows = skillSyncRunMapper.pageRuns(rowPage, triggerType, status, staleBefore());
        Page<SkillSyncRunVO> voPage = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        voPage.setRecords(rows.getRecords().stream().map(this::toRunVO).toList());
        return voPage;
    }

    // ==================== Base-class skeleton ====================

    @Override
    protected String lockKey() {
        return LOCK_KEY;
    }

    @Override
    protected String taskName() {
        return TASK_NAME;
    }

    @Override
    protected Long insertRunning(String trigger, String operator) {
        return skillSyncRunMapper.insertRunning(trigger, SkillSyncRunStatusEnum.STATUS_RUNNING, operator);
    }

    @Override
    protected SkillSyncRunVO doRun(String trigger, Long recordId, String operator, long startedAtMillis) {
        if (!isEnabled()) {
            markSkipped(recordId, SKIP_DISABLED_REASON, System.currentTimeMillis() - startedAtMillis, operator);
            SkillSyncRunVO vo = new SkillSyncRunVO();
            vo.setId(recordId);
            vo.setTriggerType(trigger);
            vo.setStatus(SkillSyncRunStatusEnum.STATUS_SKIPPED);
            vo.setSkipReason(SKIP_DISABLED_REASON);
            return vo;
        }
        return sweep(recordId, operator, startedAtMillis);
    }

    @Override
    protected void markSkipped(Long recordId, String reason, long elapsedMillis, String operator) {
        skillSyncRunMapper.markSkipped(recordId, SkillSyncRunStatusEnum.STATUS_SKIPPED,
                reason, elapsedMillis, operator);
    }

    @Override
    protected void markFailed(Long recordId, Exception e, long elapsedMillis, String operator) {
        skillSyncRunMapper.markFailed(recordId, SkillSyncRunStatusEnum.STATUS_FAILED,
                abbreviate(e.getMessage()), elapsedMillis, operator);
    }

    // ==================== Actual sweep ====================

    private SkillSyncRunVO sweep(Long recordId, String operator, long startedAtMillis) {
        int sizeLimit = Math.max(batchSize, 1);
        int scanned = 0;
        int updated = 0;
        int skipped = 0;
        List<SkillSyncRunVO.SkippedCopy> detail = new ArrayList<>();

        long pageNo = 1;
        while (true) {
            Page<CapabilitySkill> page = capabilitySkillService.page(
                    new Page<>(pageNo, sizeLimit),
                    new LambdaQueryWrapper<CapabilitySkill>()
                            // Note: deliberately no time window here. The old created_at >= now-7d
                            // was the root cause fixed here - it filtered by "install time", so
                            // old copies were never scanned again.
                            .eq(CapabilitySkill::getAutoUpdate, true)
                            .isNotNull(CapabilitySkill::getOriginSkillId)
                            .orderByAsc(CapabilitySkill::getId));
            List<CapabilitySkill> copies = page.getRecords();
            if (copies.isEmpty()) {
                break;
            }
            scanned += copies.size();
            for (CapabilitySkill copy : copies) {
                try {
                    SkillSyncOutcome outcome = capabilitySkillService.syncWithOutcome(copy);
                    if (outcome == SkillSyncOutcome.UPDATED) {
                        updated++;
                    } else {
                        skipped++;
                        if (detail.size() < MAX_DETAIL_ITEMS) {
                            detail.add(toSkippedCopy(copy, outcome));
                        }
                    }
                } catch (Exception e) {
                    // One copy failing must not affect the rest; but it must be recorded, otherwise "no effect" is again undiagnosable
                    skipped++;
                    if (detail.size() < MAX_DETAIL_ITEMS) {
                        SkillSyncRunVO.SkippedCopy failed = toSkippedCopy(copy, SkillSyncOutcome.CONCURRENT_UPDATE);
                        failed.setReason("ERROR: " + e.getMessage());
                        detail.add(failed);
                    }
                    log.warn("{} failed: copyId={}, originId={}", TASK_NAME, copy.getId(),
                            copy.getOriginSkillId(), e);
                }
            }
            if (copies.size() < sizeLimit) {
                break;
            }
            pageNo++;
        }

        // The record table itself keeps only N days: one round every 5 minutes would grow forever otherwise
        long retentionDays = resolveLogRetentionDays();
        long pruned = skillSyncRunMapper.deleteOlderThan(
                LocalDateTime.now().minusDays(retentionDays), recordId);

        long elapsed = System.currentTimeMillis() - startedAtMillis;
        skillSyncRunMapper.markSuccess(recordId, SkillSyncRunStatusEnum.STATUS_SUCCESS,
                scanned, scanned, updated, skipped,
                sizeLimit, JsonUtils.toJson(detail), elapsed, operator);
        log.info("{} done: scanned={}, updated={}, skipped={}, prunedOldRuns={}, elapsed={}ms",
                TASK_NAME, scanned, updated, skipped, pruned, elapsed);
        return null;
    }

    private SkillSyncRunVO.SkippedCopy toSkippedCopy(CapabilitySkill copy, SkillSyncOutcome outcome) {
        SkillSyncRunVO.SkippedCopy item = new SkillSyncRunVO.SkippedCopy();
        item.setCopyId(copy.getId());
        item.setOriginId(copy.getOriginSkillId());
        item.setReason(outcome.name());
        item.setOriginVersion(copy.getOriginVersion());
        return item;
    }

    private SkillSyncRunVO toRunVO(SkillSyncRunRowVO row) {
        SkillSyncRunVO vo = new SkillSyncRunVO();
        vo.setId(row.getId());
        vo.setTriggerType(row.getTriggerType());
        vo.setStatus(row.getStatus());
        vo.setScannedCount(row.getScannedCount());
        vo.setHandled(row.getHandled());
        vo.setUpdated(row.getUpdated());
        vo.setSkipped(row.getSkipped());
        vo.setBatchSize(row.getBatchSize());
        vo.setSkipReason(row.getSkipReason());
        vo.setErrorMessage(row.getErrorMessage());
        vo.setElapsedMs(row.getElapsedMs());
        vo.setStartedAt(row.getStartedAt());
        vo.setFinishedAt(row.getFinishedAt());
        vo.setStale(row.getStale());
        vo.setCreatedBy(row.getCreatedBy());
        if (row.getDetail() != null && !row.getDetail().isBlank()) {
            vo.setDetail(JsonUtils.parse(row.getDetail(), SKIP_DETAIL_TYPE));
        }
        return vo;
    }

    // ==================== Configuration ====================

    private boolean isEnabled() {
        return parseBoolean(systemConfigSpi.get(SystemConfigKeys.SKILL_AUTO_UPDATE_ENABLED, "true"), true);
    }

    private int resolveLogRetentionDays() {
        return parsePositiveInt(systemConfigSpi.get(SystemConfigKeys.SKILL_SYNC_LOG_RETENTION_DAYS,
                String.valueOf(DEFAULT_LOG_RETENTION_DAYS)), DEFAULT_LOG_RETENTION_DAYS);
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
        log.warn("{}: switch value {} is not a valid boolean, fallback {}", TASK_NAME, value, fallback);
        return fallback;
    }

    private int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            log.warn("{}: retention value {} is not an integer", TASK_NAME, raw);
        }
        return fallback;
    }

    // ==================== Constants ====================

    private static final String SKIP_DISABLED_REASON = "Auto-sync master switch skill.auto-update-enabled is false; skipped this time";

    private static final TypeReference<List<SkillSyncRunVO.SkippedCopy>> SKIP_DETAIL_TYPE =
            new TypeReference<>() {
            };
}
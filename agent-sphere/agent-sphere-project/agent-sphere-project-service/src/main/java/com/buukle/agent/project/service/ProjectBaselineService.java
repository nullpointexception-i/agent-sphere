package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.domain.PmProjectAnalysisBatch;
import com.buukle.agent.project.domain.PmProjectContextEvent;
import com.buukle.agent.project.dtvo.ProjectAssistantSessionVO;
import com.buukle.agent.project.dtvo.ProjectBaselineVO;
import com.buukle.agent.project.dtvo.enums.ProjectAnalysisBatchStatus;
import com.buukle.agent.project.dtvo.enums.ProjectAnalysisStatus;
import com.buukle.agent.project.repository.PmProjectAnalysisBatchMapper;
import com.buukle.agent.project.repository.PmProjectContextEventMapper;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.spi.ProjectAssistantRunCommand;
import com.buukle.agent.project.spi.ProjectAssistantRunPort;
import com.buukle.agent.project.spi.ProjectAssistantRunResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Project-level baseline pipeline.
 *
 * <p>Incremental context events are collected into micro-batches, analyzed on the canonical
 * project-assistant session, and the resulting assistant comments land in requirement threads.
 * A batch is only analyzed while the project's {@code autoAnalyze} switch is on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectBaselineService {

    static final String LOCK_PREFIX = "project:baseline:";
    private static final long LOCK_LEASE_SECONDS = 180L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int MAX_SUMMARY_CHARS = 500;

    private final PmProjectContextEventMapper eventMapper;
    private final PmProjectAnalysisBatchMapper batchMapper;
    private final PmProjectMapper projectMapper;
    private final ProjectAssistantConfigService configService;
    private final ProjectAssistantService assistantService;
    private final ProjectAssistantRunPort runPort;
    private final RunSpi runSpi;
    private final UserSpi userSpi;
    private final RedissonClient redissonClient;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${buukle.agent.project-baseline.enabled:true}")
    private boolean enabled;
    @Value("${buukle.agent.project-baseline.max-events:20}")
    private int maxEvents;
    @Value("${buukle.agent.project-baseline.max-chars:12000}")
    private int maxChars;
    @Value("${buukle.agent.project-baseline.max-wait-ms:3000}")
    private long maxWaitMs;
    @Value("${buukle.agent.project-baseline.retry.max-attempts:5}")
    private int retryMaxAttempts;
    @Value("${buukle.agent.project-baseline.retry.base-backoff-ms:5000}")
    private long retryBaseBackoffMs;
    @Value("${buukle.agent.project-baseline.run-timeout-seconds:900}")
    private long runTimeoutSeconds;

    @Scheduled(fixedDelayString = "${buukle.agent.project-baseline.interval-ms:3000}")
    public void run() {
        if (!enabled) {
            return;
        }
        clearContext();
        List<Long> projectIds = eventMapper.selectPendingProjectIds(50);
        for (Long projectId : projectIds) {
            try {
                processProject(projectId);
            } catch (Exception e) {
                log.warn("Project baseline failed: project={}, error={}", projectId, e.getMessage());
            } finally {
                clearContext();
            }
        }
    }

    /** Baseline pipeline status for the project assistant panel. */
    public ProjectBaselineVO status(Long projectId) {
        ProjectBaselineVO vo = new ProjectBaselineVO();
        vo.setProjectId(projectId);
        if (projectId == null) {
            return vo;
        }
        vo.setAutoAnalyze(configService.isAutoAnalyze(projectId));
        vo.setPendingEvents(eventMapper.selectCount(new LambdaQueryWrapper<PmProjectContextEvent>()
                .eq(PmProjectContextEvent::getProjectId, projectId)
                .eq(PmProjectContextEvent::getAnalysisStatus,
                        ProjectAnalysisStatus.PENDING.value())));
        vo.setRunningBatches(batchMapper.selectCount(
                new LambdaQueryWrapper<PmProjectAnalysisBatch>()
                        .eq(PmProjectAnalysisBatch::getProjectId, projectId)
                        .eq(PmProjectAnalysisBatch::getStatus,
                                ProjectAnalysisBatchStatus.RUNNING.value())));
        PmProjectAnalysisBatch last = batchMapper.selectOne(
                new LambdaQueryWrapper<PmProjectAnalysisBatch>()
                        .eq(PmProjectAnalysisBatch::getProjectId, projectId)
                        .orderByDesc(PmProjectAnalysisBatch::getId)
                        .last("LIMIT 1"));
        if (last != null) {
            vo.setLastBatchStatus(last.getStatus());
            vo.setLastBatchAt(last.getFinishedAt() == null
                    ? last.getCreatedAt() : last.getFinishedAt());
            vo.setLastBatchError(last.getErrorMessage());
        }
        return vo;
    }

    /** Real-time wake from the write path; the scheduled scan remains the compensation. */
    public void wake(Long projectId) {
        if (!enabled || projectId == null || !configService.isAutoAnalyze(projectId)) {
            return;
        }
        executor.submit(() -> {
            clearContext();
            try {
                processProject(projectId);
            } catch (Exception e) {
                log.warn("Project baseline wake failed: project={}, error={}",
                        projectId, e.getMessage());
            } finally {
                clearContext();
            }
        });
    }

    private void processProject(Long projectId) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + projectId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            PmProject project = projectMapper.selectById(projectId);
            if (project == null) {
                return;
            }
            bind(project);
            if (!configService.isAutoAnalyze(projectId)) {
                markSkipped(projectId, null);
                return;
            }
            if (!batchReady(projectId)) {
                return;
            }
            List<PmProjectContextEvent> events = pendingEvents(projectId);
            if (events.isEmpty()) {
                return;
            }
            processBatch(project, events);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            clearContext();
        }
    }

    private boolean batchReady(Long projectId) {
        long pending = eventMapper.selectCount(pendingWrapper(projectId));
        if (pending >= Math.max(1, maxEvents)) {
            return true;
        }
        PmProjectContextEvent oldest = eventMapper.selectOne(pendingWrapper(projectId)
                .orderByAsc(PmProjectContextEvent::getId)
                .last("LIMIT 1"));
        if (oldest == null || oldest.getCreatedAt() == null) {
            return false;
        }
        return Duration.between(oldest.getCreatedAt(), LocalDateTime.now()).toMillis()
                >= Math.max(0, maxWaitMs);
    }

    private List<PmProjectContextEvent> pendingEvents(Long projectId) {
        List<PmProjectContextEvent> rows = eventMapper.selectList(pendingWrapper(projectId)
                .orderByAsc(PmProjectContextEvent::getId)
                .last("LIMIT " + Math.max(1, maxEvents)));
        List<PmProjectContextEvent> selected = new ArrayList<>();
        int used = 0;
        for (PmProjectContextEvent row : rows) {
            int cost = row.getSummary() == null ? 0 : row.getSummary().length();
            if (!selected.isEmpty() && used + cost > Math.max(1, maxChars)) {
                break;
            }
            selected.add(row);
            used += cost;
        }
        return selected;
    }

    private void processBatch(PmProject project, List<PmProjectContextEvent> events) {
        String batchKey = project.getId() + ":" + events.get(0).getId() + ":"
                + System.currentTimeMillis();
        PmProjectAnalysisBatch batch = new PmProjectAnalysisBatch();
        batch.setTenantId(project.getTenantId());
        batch.setProjectId(project.getId());
        batch.setBatchKey(batchKey);
        batch.setStatus(ProjectAnalysisBatchStatus.RUNNING.value());
        batch.setEventCount(events.size());
        batch.setCharCount(events.stream().mapToInt(e -> e.getSummary() == null
                ? 0 : e.getSummary().length()).sum());
        batch.setAttempts(maxAttempt(events));
        batch.setStartedAt(LocalDateTime.now());
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException e) {
            return;
        }
        List<Long> ids = events.stream().map(PmProjectContextEvent::getId).toList();
        markDispatched(ids, batchKey);
        ProjectAssistantSessionVO session;
        try {
            session = assistantService.ensureCanonicalSession(project.getTenantId(), project);
        } catch (Exception e) {
            handleFailure(batch, events, "canonical session unavailable: " + e.getMessage());
            return;
        }
        if (session == null || session.getCanonicalSessionId() == null) {
            handleFailure(batch, events, "canonical session unavailable");
            return;
        }
        batch.setCanonicalSessionId(session.getCanonicalSessionId());
        batchMapper.updateById(batch);
        ProjectAssistantRunResult result;
        try {
            result = runPort.runCanonical(new ProjectAssistantRunCommand(
                    project.getTenantId(), project.getId(), project.getOwnerUserId(),
                    usernameOf(project.getOwnerUserId()), session.getCanonicalSessionId(), null,
                    buildPrompt(project, events), RunMode.READ_ONLY.name(), true,
                    ProjectAssistantConstants.SOURCE_AUTO, batchKey));
        } catch (Exception e) {
            handleFailure(batch, events, "analysis run failed: " + e.getMessage());
            return;
        }
        if (result == null || result.runId() == null) {
            handleFailure(batch, events, "analysis run was not accepted");
            return;
        }
        batch.setRunId(result.runId());
        batchMapper.updateById(batch);
        log.info("Project baseline batch started: project={}, batch={}, events={}, run={}",
                project.getId(), batchKey, events.size(), result.runId());
    }

    @Scheduled(fixedDelayString = "${buukle.agent.project-baseline.reconcile-interval-ms:5000}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        clearContext();
        List<PmProjectAnalysisBatch> running = batchMapper.selectList(
                new LambdaQueryWrapper<PmProjectAnalysisBatch>()
                        .eq(PmProjectAnalysisBatch::getStatus,
                                ProjectAnalysisBatchStatus.RUNNING.value())
                        .orderByAsc(PmProjectAnalysisBatch::getId)
                        .last("LIMIT 50"));
        for (PmProjectAnalysisBatch batch : running) {
            try {
                reconcileBatch(batch);
            } catch (Exception e) {
                log.warn("Project baseline reconcile failed: batch={}, error={}",
                        batch.getId(), e.getMessage());
            } finally {
                clearContext();
            }
        }
    }

    private void reconcileBatch(PmProjectAnalysisBatch batch) {
        if (batch.getRunId() == null) {
            if (expired(batch.getStartedAt(), runTimeoutSeconds)) {
                handleFailure(batch, null, "analysis run was never created");
            }
            return;
        }
        RunVO run = runSpi.getRun(batch.getRunId());
        if (run == null) {
            if (expired(batch.getStartedAt(), runTimeoutSeconds)) {
                handleFailure(batch, null, "analysis run disappeared");
            }
            return;
        }
        if (RunStatus.COMPLETED.name().equals(run.getStatus())) {
            markCompleted(batch);
            return;
        }
        if (RunStatus.FAILED.name().equals(run.getStatus())
                || RunStatus.CANCELLED.name().equals(run.getStatus())) {
            handleFailure(batch, null, run.getErrorMessage() == null
                    ? "analysis run " + run.getStatus() : run.getErrorMessage());
            return;
        }
        if (expired(batch.getStartedAt(), runTimeoutSeconds)) {
            handleFailure(batch, null, "analysis run timed out");
        }
    }

    private void markCompleted(PmProjectAnalysisBatch batch) {
        boolean changed = batchMapper.update(null,
                new LambdaUpdateWrapper<PmProjectAnalysisBatch>()
                        .eq(PmProjectAnalysisBatch::getId, batch.getId())
                        .eq(PmProjectAnalysisBatch::getStatus,
                                ProjectAnalysisBatchStatus.RUNNING.value())
                        .set(PmProjectAnalysisBatch::getStatus,
                                ProjectAnalysisBatchStatus.COMPLETED.value())
                        .set(PmProjectAnalysisBatch::getFinishedAt, LocalDateTime.now())) > 0;
        if (!changed) {
            return;
        }
        eventMapper.update(null, new LambdaUpdateWrapper<PmProjectContextEvent>()
                .eq(PmProjectContextEvent::getAnalysisBatchId, batch.getBatchKey())
                .set(PmProjectContextEvent::getAnalysisStatus,
                        ProjectAnalysisStatus.PROCESSED.value())
                .set(PmProjectContextEvent::getAnalysisProcessedAt, LocalDateTime.now()));
    }

    private void handleFailure(PmProjectAnalysisBatch batch, List<PmProjectContextEvent> events,
                               String error) {
        int attempts = (batch.getAttempts() == null ? 0 : batch.getAttempts()) + 1;
        boolean exhausted = attempts >= Math.max(1, retryMaxAttempts);
        LocalDateTime now = LocalDateTime.now();
        batchMapper.update(null, new LambdaUpdateWrapper<PmProjectAnalysisBatch>()
                .eq(PmProjectAnalysisBatch::getId, batch.getId())
                .set(PmProjectAnalysisBatch::getStatus, (exhausted
                        ? ProjectAnalysisBatchStatus.FAILED
                        : ProjectAnalysisBatchStatus.CANCELLED).value())
                .set(PmProjectAnalysisBatch::getAttempts, attempts)
                .set(PmProjectAnalysisBatch::getErrorMessage, truncate(error))
                .set(PmProjectAnalysisBatch::getFinishedAt, now));
        if (exhausted) {
            markBatchEventsSkipped(batch.getBatchKey());
            log.warn("Project baseline batch exhausted retries: batch={}, error={}",
                    batch.getBatchKey(), error);
            return;
        }
        LocalDateTime retryAt = now.plusNanos(
                TimeUnit.MILLISECONDS.toNanos(backoffMillis(attempts)));
        eventMapper.update(null, new LambdaUpdateWrapper<PmProjectContextEvent>()
                .eq(PmProjectContextEvent::getAnalysisBatchId, batch.getBatchKey())
                .set(PmProjectContextEvent::getAnalysisStatus,
                        ProjectAnalysisStatus.PENDING.value())
                .set(PmProjectContextEvent::getAnalysisBatchId, null)
                .set(PmProjectContextEvent::getAnalysisNextRetryAt, retryAt));
        log.info("Project baseline batch retry scheduled: batch={}, attempt={}, retryAt={}",
                batch.getBatchKey(), attempts, retryAt);
    }

    private void markDispatched(List<Long> ids, String batchKey) {
        eventMapper.update(null, new LambdaUpdateWrapper<PmProjectContextEvent>()
                .in(PmProjectContextEvent::getId, ids)
                .eq(PmProjectContextEvent::getAnalysisStatus,
                        ProjectAnalysisStatus.PENDING.value())
                .set(PmProjectContextEvent::getAnalysisStatus,
                        ProjectAnalysisStatus.DISPATCHED.value())
                .set(PmProjectContextEvent::getAnalysisBatchId, batchKey)
                .set(PmProjectContextEvent::getAnalysisNextRetryAt, null)
                .setSql("analysis_attempts = analysis_attempts + 1"));
    }

    private void markSkipped(Long projectId, String batchKey) {
        LambdaUpdateWrapper<PmProjectContextEvent> wrapper =
                new LambdaUpdateWrapper<PmProjectContextEvent>()
                        .eq(PmProjectContextEvent::getAnalysisStatus,
                                ProjectAnalysisStatus.PENDING.value())
                        .set(PmProjectContextEvent::getAnalysisStatus,
                                ProjectAnalysisStatus.SKIPPED.value())
                        .set(PmProjectContextEvent::getAnalysisProcessedAt, LocalDateTime.now());
        if (projectId != null) {
            wrapper.eq(PmProjectContextEvent::getProjectId, projectId);
        }
        if (batchKey != null) {
            wrapper.eq(PmProjectContextEvent::getAnalysisBatchId, batchKey);
        }
        eventMapper.update(null, wrapper);
    }

    /** Batch events already left PENDING; close them without retrying. */
    private void markBatchEventsSkipped(String batchKey) {
        if (batchKey == null) {
            return;
        }
        eventMapper.update(null, new LambdaUpdateWrapper<PmProjectContextEvent>()
                .eq(PmProjectContextEvent::getAnalysisBatchId, batchKey)
                .set(PmProjectContextEvent::getAnalysisStatus,
                        ProjectAnalysisStatus.SKIPPED.value())
                .set(PmProjectContextEvent::getAnalysisProcessedAt, LocalDateTime.now()));
    }

    private LambdaQueryWrapper<PmProjectContextEvent> pendingWrapper(Long projectId) {
        return new LambdaQueryWrapper<PmProjectContextEvent>()
                .eq(PmProjectContextEvent::getProjectId, projectId)
                .eq(PmProjectContextEvent::getAnalysisStatus,
                        ProjectAnalysisStatus.PENDING.value())
                .and(w -> w.isNull(PmProjectContextEvent::getAnalysisNextRetryAt)
                        .or().le(PmProjectContextEvent::getAnalysisNextRetryAt,
                                LocalDateTime.now()));
    }

    private String buildPrompt(PmProject project, List<PmProjectContextEvent> events) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the project baseline analyst. Analyze only the incremental ")
                .append("changes below; do not repeat older findings.\n")
                .append("Project: ").append(project.getName()).append('\n')
                .append("Changes:\n");
        for (PmProjectContextEvent event : events) {
            String summary = event.getSummary() == null ? "" : event.getSummary();
            if (summary.length() > MAX_SUMMARY_CHARS) {
                summary = summary.substring(0, MAX_SUMMARY_CHARS);
            }
            prompt.append("- [").append(event.getCreatedAt() == null ? ""
                            : event.getCreatedAt().format(TIME)).append("] ")
                    .append(event.getEventType())
                    .append(event.getRequirementId() == null ? ""
                            : " requirement#" + event.getRequirementId())
                    .append(": ").append(summary).append('\n');
        }
        prompt.append("""
                Instructions:
                1. Use project_query to inspect the affected requirements, members, and avatars when needed.
                2. Identify concrete risks, blockers, priority conflicts, or decisions that need action.
                3. Use project_comment to reply in the most relevant requirement thread. Create at most 3 comments for this batch; only comment when the finding is actionable.
                4. Mention a member or avatar only when their action is required; avatar replies are generated automatically.
                5. Do not modify project data and do not ask humans questions.
                """);
        return prompt.toString();
    }

    private long backoffMillis(int attempts) {
        long base = Math.max(1000L, retryBaseBackoffMs);
        int shift = Math.min(Math.max(0, attempts - 1), 10);
        return Math.min(base << shift, TimeUnit.MINUTES.toMillis(30));
    }

    private static int maxAttempt(PmProjectContextEvent event) {
        return event.getAnalysisAttempts() == null ? 0 : event.getAnalysisAttempts();
    }

    private static int maxAttempt(List<PmProjectContextEvent> events) {
        return events.stream().mapToInt(ProjectBaselineService::maxAttempt).max().orElse(0);
    }

    private static boolean expired(LocalDateTime startedAt, long timeoutSeconds) {
        return startedAt != null && Duration.between(startedAt, LocalDateTime.now()).toSeconds()
                >= Math.max(30L, timeoutSeconds);
    }

    private void bind(PmProject project) {
        Long tenantId = project.getTenantId() == null ? 0L : project.getTenantId();
        Long ownerId = project.getOwnerUserId();
        String username = usernameOf(ownerId);
        AuthContext.clear();
        AuthContext.setUserId(ownerId);
        AuthContext.setUsername(username);
        AuthContext.setSuperAdmin(false);
        AuthContext.setPermissions(Set.of());
        TenantContext.set(tenantId);
        TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, username));
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return "system";
        }
        UserIdentityVO identity = userSpi.identityMap(Set.of(userId)).get(userId);
        if (identity == null || !StringUtils.hasText(identity.getUsername())) {
            return String.valueOf(userId);
        }
        return identity.getUsername();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1900 ? value : value.substring(0, 1900);
    }

    private static void clearContext() {
        AuthContext.clear();
        TenantContext.clear();
        TenantScopeHolder.clear();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}

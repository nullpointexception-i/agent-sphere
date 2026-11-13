package com.buukle.agent.project.service;

import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.dtvo.ProjectAssistantSessionVO;
import com.buukle.agent.project.event.ProjectRequirementChangedEvent;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.spi.ProjectAssistantRunCommand;
import com.buukle.agent.project.spi.ProjectAssistantRunPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Debounced project analysis after project/requirement writes.
 *
 * <p>The switch lives in {@code pm_project.config.projectRouter.autoAnalyze} and is off by
 * default. Writes only refresh one analysis per project per debounce window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAutoAnalysisListener {

    private static final String PROMPT = """
            Analyze the project's recent changes in read-only mode:
            1. Use project_query to read the project overview, open/overdue/high-priority requirements, and recent comments.
            2. Identify risks, blockers, priority conflicts, and next-step suggestions.
            3. Use project_insight to save 1-3 insights (insightType=SUMMARY/RISK/PROGRESS, severity=INFO/WARN/HIGH).
            4. Do not modify any project or requirement data and do not ask humans any questions.
            """;

    private final PmProjectMapper projectMapper;
    private final ProjectAssistantService assistantService;
    private final ProjectAssistantConfigService configService;
    private final ProjectAssistantRunPort runPort;
    private final UserSpi userSpi;
    private final RedissonClient redissonClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(ProjectRequirementChangedEvent event) {
        if (event == null || event.projectId() == null) {
            return;
        }
        try {
            PmProject project = projectMapper.selectById(event.projectId());
            if (project == null || !configService.isAutoAnalyze(event.projectId())) {
                return;
            }
            RBucket<Boolean> bucket = redissonClient.getBucket(
                    ProjectAssistantConstants.AUTO_ANALYZE_LOCK_PREFIX + event.projectId());
            if (!bucket.trySet(Boolean.TRUE,
                    ProjectAssistantConstants.AUTO_ANALYZE_DEBOUNCE_MINUTES, TimeUnit.MINUTES)) {
                return;
            }
            ProjectAssistantSessionVO session = assistantService.ensureSession(event.projectId());
            ProjectAssistantRunCommand command = new ProjectAssistantRunCommand(
                    event.tenantId() != null ? event.tenantId() : project.getTenantId(),
                    event.projectId(), project.getOwnerUserId(),
                    usernameOf(project.getOwnerUserId()), session.getCanonicalSessionId(),
                    event.requirementId(), PROMPT, RunMode.READ_ONLY.name(), true,
                    ProjectAssistantConstants.SOURCE_AUTO, null);
            runPort.runCanonical(command);
        } catch (Exception e) {
            log.warn("Auto project analysis skipped: project={}, error={}",
                    event.projectId(), e.getMessage());
        }
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return "system";
        }
        UserIdentityVO identity = userSpi.identityMap(Set.of(userId)).get(userId);
        return identity == null || !StringUtils.hasText(identity.getUsername())
                ? String.valueOf(userId) : identity.getUsername();
    }
}

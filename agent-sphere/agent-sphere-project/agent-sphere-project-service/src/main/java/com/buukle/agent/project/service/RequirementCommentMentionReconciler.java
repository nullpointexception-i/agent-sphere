package com.buukle.agent.project.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.project.domain.PmRequirement;
import com.buukle.agent.project.domain.PmRequirementComment;
import com.buukle.agent.project.domain.PmRequirementCommentMention;
import com.buukle.agent.project.dtvo.enums.CommentMentionType;
import com.buukle.agent.project.event.RequirementCommentMentionedEvent;
import com.buukle.agent.project.repository.PmRequirementCommentMapper;
import com.buukle.agent.project.repository.PmRequirementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Compensation for mention events lost to a restart or a crashed worker.
 *
 * <p>Pending mentions are re-published and stale PROCESSING claims are released.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementCommentMentionReconciler {

    private static final String LOCK_KEY = "scheduler:project-mention-reconcile";
    private static final long LOCK_LEASE_SECONDS = 60;
    private static final int SCAN_LIMIT = 50;

    private final RequirementCommentMentionService mentionService;
    private final PmRequirementCommentMapper commentMapper;
    private final PmRequirementMapper requirementMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${buukle.agent.project-mention.reconcile-interval-ms:30000}")
    public void run() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            clearContext();
            for (PmRequirementCommentMention mention : mentionService.findStaleProcessing(SCAN_LIMIT)) {
                mentionService.resetProcessing(mention.getId());
                log.warn("Released stale project mention claim: mention={}", mention.getId());
            }
            for (PmRequirementCommentMention mention : mentionService.findDuePending(SCAN_LIMIT)) {
                try {
                    republish(mention);
                } catch (Exception e) {
                    log.warn("Project mention republish failed: mention={}, error={}",
                            mention.getId(), e.getMessage());
                } finally {
                    clearContext();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Project mention reconcile failed: {}", e.getMessage());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            clearContext();
        }
    }

    private void republish(PmRequirementCommentMention mention) {
        if (!CommentMentionType.AVATAR.value().equals(mention.getMentionType())) {
            return;
        }
        TenantContext.set(mention.getTenantId());
        PmRequirementComment comment = commentMapper.selectById(mention.getCommentId());
        if (comment == null) {
            mentionService.markFailed(mention.getId(), "original comment no longer exists");
            return;
        }
        PmRequirement requirement = requirementMapper.selectById(comment.getRequirementId());
        if (requirement == null) {
            mentionService.markFailed(mention.getId(), "requirement no longer exists");
            return;
        }
        eventPublisher.publishEvent(new RequirementCommentMentionedEvent(
                mention.getTenantId(), requirement.getProjectId(), comment.getRequirementId(),
                mention.getCommentId(), mention.getId(), mention.getMentionType(),
                mention.getTargetUserId(), mention.getTargetAgentId(), comment.getUserId()));
    }

    private static void clearContext() {
        AuthContext.clear();
        TenantContext.clear();
        TenantScopeHolder.clear();
    }
}

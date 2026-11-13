package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.domain.PmRequirement;
import com.buukle.agent.project.domain.PmRequirementComment;
import com.buukle.agent.project.domain.PmRequirementCommentMention;
import com.buukle.agent.project.dtvo.RequirementCommentVO;
import com.buukle.agent.project.dtvo.enums.CommentAuthorType;
import com.buukle.agent.project.event.RequirementCommentMentionedEvent;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.repository.PmRequirementCommentMapper;
import com.buukle.agent.project.repository.PmRequirementMapper;
import com.buukle.agent.superagent.dtvo.enums.AgentType;
import com.buukle.agent.superagent.spi.AvatarConsultCommand;
import com.buukle.agent.superagent.spi.AvatarConsultResult;
import com.buukle.agent.superagent.spi.AvatarConsultSpi;
import com.buukle.agent.superagent.spi.AvatarInfo;
import com.buukle.agent.superagent.spi.LocalProjectMentionCommand;
import com.buukle.agent.superagent.spi.LocalProjectMentionDispatchPort;
import com.buukle.agent.superagent.spi.LocalProjectMentionDispatchResult;
import com.buukle.agent.superagent.spi.ProjectThreadSpi;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns an @ mention of an avatar into an investigation and a threaded reply.
 *
 * <p>The mention row is claimed atomically, so a redelivered event cannot create two replies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementCommentMentionListener {

    private static final int MAX_COMMENTS_IN_PROMPT = 20;

    private final RequirementCommentMentionService mentionService;
    private final RequirementCommentService commentService;
    private final SuperAgentDirectorySpi directorySpi;
    private final UserSpi userSpi;
    private final PmProjectMapper projectMapper;
    private final PmRequirementMapper requirementMapper;
    private final PmRequirementCommentMapper commentMapper;
    private final AvatarConsultSpi avatarConsultSpi;
    private final ObjectProvider<LocalProjectMentionDispatchPort> localMentionPortProvider;
    private final ProjectThreadSpi projectThreadSpi;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMentioned(RequirementCommentMentionedEvent event) {
        if (event == null || event.mentionId() == null) {
            return;
        }
        executor.submit(() -> handle(event));
    }

    private void handle(RequirementCommentMentionedEvent event) {
        ContextSnapshot snapshot = ContextSnapshot.capture();
        try {
            bind(event.tenantId(), event.targetUserId());
            if (!mentionService.claim(event.mentionId())) {
                return;
            }
            PmRequirementCommentMention mention = mentionService.find(event.mentionId());
            if (mention == null || mention.getTargetAgentId() == null) {
                mentionService.markFailed(event.mentionId(), "mention target is missing");
                return;
            }
            PmProject project = projectMapper.selectById(event.projectId());
            PmRequirement requirement = requirementMapper.selectById(event.requirementId());
            AvatarInfo avatar = directorySpi.findAvatar(mention.getTargetAgentId());
            if (project == null || requirement == null || avatar == null) {
                mentionService.markFailed(event.mentionId(),
                        "project, requirement, or avatar no longer exists");
                return;
            }
            String username = usernameOf(mention.getTargetUserId());
            String question = buildQuestion(project, requirement, event.commentId(),
                    event.actorUserId());
            if (AgentType.LOCAL.equals(avatar.agentType())) {
                dispatchLocal(event, mention, project, avatar, question, username);
                return;
            }
            if (avatar.routerInstanceId() == null) {
                mentionService.markFailed(event.mentionId(),
                        "avatar has no runnable instance");
                return;
            }
            AvatarConsultResult result = avatarConsultSpi.consult(new AvatarConsultCommand(
                    event.tenantId(), mention.getTargetUserId(), username, avatar.agentId(),
                    question, ProjectAssistantConstants.AVATAR_CONSULT_TIMEOUT_SECONDS));
            if (!result.ok()) {
                retryOrFail(mention, result.error());
                return;
            }
            RequirementCommentVO reply = commentService.add(
                    new RequirementCommentService.RequirementCommentCommand(
                            event.tenantId(), project.getId(), requirement.getId(),
                            mention.getTargetUserId(), CommentAuthorType.AVATAR.value(),
                            avatar.name(), event.commentId(), result.answer(), null, null,
                            null, null, null, "mention-reply:" + mention.getId()));
            mentionService.markProcessed(event.mentionId(), reply.getId());
        } catch (Exception e) {
            log.warn("Avatar mention reply failed: mention={}, error={}",
                    event.mentionId(), e.getMessage());
            try {
                bind(event.tenantId(), event.targetUserId());
                PmRequirementCommentMention mention = mentionService.find(event.mentionId());
                if (mention != null) {
                    retryOrFail(mention, e.getMessage());
                }
            } catch (Exception ignored) {
                // best effort
            }
        } finally {
            snapshot.restore();
        }
    }

    private void dispatchLocal(RequirementCommentMentionedEvent event,
                               PmRequirementCommentMention mention, PmProject project,
                               AvatarInfo avatar, String question, String username) {
        LocalProjectMentionDispatchPort port = localMentionPortProvider.getIfAvailable();
        if (port == null) {
            mentionService.markFailed(event.mentionId(),
                    "local runtime dispatch is unavailable");
            return;
        }
        Long threadId = projectThreadSpi.ensureProjectThread(
                event.tenantId(), project.getId(), project.getName(), username);
        LocalProjectMentionDispatchResult result = port.dispatch(
                new LocalProjectMentionCommand(
                        event.tenantId(), avatar.agentId(), event.actorUserId(),
                        displayName(event.actorUserId()), project.getId(),
                        event.requirementId(), event.commentId(), event.mentionId(),
                        threadId, project.getName(), question));
        if (result == null) {
            retryOrFail(mention, "local runtime dispatch returned no result");
            return;
        }
        if (result.accepted()) {
            return;
        }
        if (result.retryable()) {
            retryOrFail(mention, result.message());
            return;
        }
        mentionService.markFailed(event.mentionId(), result.message());
    }

    private void retryOrFail(PmRequirementCommentMention mention, String error) {
        int attempts = mention.getAttempts() == null ? 1 : mention.getAttempts();
        if (attempts >= ProjectAssistantConstants.MENTION_MAX_ATTEMPTS
                || !retryable(error)) {
            mentionService.markFailed(mention.getId(), error);
            return;
        }
        long seconds = Math.min(
                ProjectAssistantConstants.MENTION_RETRY_BASE_SECONDS
                        * (1L << Math.min(attempts - 1, 6)),
                ProjectAssistantConstants.MENTION_RETRY_MAX_SECONDS);
        mentionService.scheduleRetry(mention.getId(), error,
                LocalDateTime.now().plusSeconds(seconds));
    }

    private static boolean retryable(String error) {
        if (error == null) {
            return false;
        }
        String text = error.toLowerCase(java.util.Locale.ROOT);
        return text.contains("busy") || text.contains("timed out")
                || text.contains("timeout") || text.contains("offline")
                || text.contains("temporarily") || text.contains("no result");
    }

    private String buildQuestion(PmProject project, PmRequirement requirement, Long commentId,
                                 Long actorUserId) {
        StringBuilder question = new StringBuilder();
        question.append("A project member mentioned you in a requirement comment. ")
                .append("Investigate and reply in the same thread.\n")
                .append("Project: ").append(project.getName()).append('\n')
                .append("Requirement ").append(requirement.getRequirementKey())
                .append(": ").append(requirement.getTitle()).append('\n');
        if (StringUtils.hasText(requirement.getDescription())) {
            question.append("Description: ").append(requirement.getDescription()).append('\n');
        }
        question.append("Status: ").append(requirement.getStatus())
                .append(", priority: ").append(requirement.getPriority()).append('\n');
        PmRequirementComment mentionComment = commentMapper.selectById(commentId);
        if (mentionComment != null) {
            question.append("The comment by ")
                    .append(displayName(actorUserId))
                    .append(": ").append(mentionComment.getContent()).append('\n');
        }
        List<PmRequirementComment> recent = commentMapper.selectList(
                new LambdaQueryWrapper<PmRequirementComment>()
                        .eq(PmRequirementComment::getRequirementId, requirement.getId())
                        .orderByDesc(PmRequirementComment::getCreatedAt)
                        .orderByDesc(PmRequirementComment::getId)
                        .last("LIMIT " + MAX_COMMENTS_IN_PROMPT));
        if (!recent.isEmpty()) {
            question.append("Recent thread:\n");
            for (int i = recent.size() - 1; i >= 0; i--) {
                PmRequirementComment comment = recent.get(i);
                if (Objects.equals(comment.getId(), commentId)) {
                    continue;
                }
                question.append("- ").append(comment.getAuthorName() == null
                                ? displayName(comment.getUserId()) : comment.getAuthorName())
                        .append(": ").append(comment.getContent()).append('\n');
            }
        }
        question.append("Answer directly with findings, risks, and next steps. ")
                .append("Do not modify project data.");
        return question.toString();
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

    private String displayName(Long userId) {
        if (userId == null) {
            return "Project member";
        }
        UserIdentityVO identity = userSpi.identityMap(Set.of(userId)).get(userId);
        if (identity == null) {
            return "Project member";
        }
        if (StringUtils.hasText(identity.getDisplayName())) {
            return identity.getDisplayName();
        }
        if (StringUtils.hasText(identity.getEnglishName())) {
            return identity.getEnglishName();
        }
        return StringUtils.hasText(identity.getUsername())
                ? identity.getUsername() : "Project member";
    }

    private void bind(Long tenantId, Long userId) {
        AuthContext.clear();
        AuthContext.setUserId(userId);
        AuthContext.setUsername(usernameOf(userId));
        AuthContext.setSuperAdmin(false);
        AuthContext.setPermissions(Set.of());
        TenantContext.set(tenantId);
        TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, usernameOf(userId)));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /** Restore the scheduler thread's context after the virtual-thread task finishes. */
    private record ContextSnapshot(
            String username,
            String displayName,
            Long userId,
            boolean superAdmin,
            Set<String> permissions,
            Long tenantId,
            TenantScope scope
    ) {
        static ContextSnapshot capture() {
            return new ContextSnapshot(
                    AuthContext.getUsername(), AuthContext.getDisplayName(), AuthContext.getUserId(),
                    AuthContext.isSuperAdmin(), AuthContext.getPermissions(),
                    TenantContext.get(), TenantScopeHolder.get());
        }

        void restore() {
            AuthContext.clear();
            if (username != null) AuthContext.setUsername(username);
            if (displayName != null) AuthContext.setDisplayName(displayName);
            if (userId != null) AuthContext.setUserId(userId);
            AuthContext.setSuperAdmin(superAdmin);
            if (permissions != null) AuthContext.setPermissions(permissions);
            TenantContext.set(tenantId);
            if (scope == null) {
                TenantScopeHolder.clear();
            } else {
                TenantScopeHolder.start(scope);
            }
        }
    }
}

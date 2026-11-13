package com.buukle.agent.project.service;

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
import com.buukle.agent.project.dtvo.enums.CommentMentionStatus;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.repository.PmRequirementCommentMapper;
import com.buukle.agent.project.repository.PmRequirementMapper;
import com.buukle.agent.superagent.spi.ProjectMentionTaskCompletedEvent;
import com.buukle.agent.superagent.spi.ProjectMentionTaskFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.Set;

/** Writes a local avatar's project-mention answer back into the requirement thread. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMentionReplyListener {

    private static final String DEFAULT_AVATAR_NAME = "Avatar";

    private final RequirementCommentMentionService mentionService;
    private final RequirementCommentService commentService;
    private final PmProjectMapper projectMapper;
    private final PmRequirementMapper requirementMapper;
    private final PmRequirementCommentMapper commentMapper;
    private final UserSpi userSpi;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCompleted(ProjectMentionTaskCompletedEvent event) {
        if (event == null || event.mentionId() == null) {
            return;
        }
        ContextSnapshot snapshot = ContextSnapshot.capture();
        try {
            bind(event.tenantId(), null);
            PmRequirementCommentMention mention = mentionService.find(event.mentionId());
            if (mention == null || !StringUtils.hasText(event.text())) {
                return;
            }
            if (CommentMentionStatus.PROCESSED.value().equals(mention.getStatus())) {
                return;
            }
            bind(event.tenantId(), mention.getTargetUserId());
            PmRequirementComment original = commentMapper.selectById(mention.getCommentId());
            if (original == null) {
                mentionService.markFailed(event.mentionId(), "original comment no longer exists");
                return;
            }
            PmRequirement requirement = requirementMapper.selectById(original.getRequirementId());
            if (requirement == null) {
                mentionService.markFailed(event.mentionId(), "requirement no longer exists");
                return;
            }
            PmProject project = projectMapper.selectById(requirement.getProjectId());
            if (project == null) {
                mentionService.markFailed(event.mentionId(), "project no longer exists");
                return;
            }
            RequirementCommentVO reply = commentService.add(
                    new RequirementCommentService.RequirementCommentCommand(
                            event.tenantId(), project.getId(), requirement.getId(),
                            mention.getTargetUserId(), CommentAuthorType.AVATAR.value(),
                            StringUtils.hasText(event.agentName())
                                    ? event.agentName() : DEFAULT_AVATAR_NAME,
                            mention.getCommentId(), event.text(), null, null,
                            null, null, null, "mention-reply:" + mention.getId()));
            mentionService.markProcessed(mention.getId(), reply.getId());
        } catch (Exception e) {
            log.warn("Project mention reply failed: mention={}, error={}",
                    event.mentionId(), e.getMessage());
            try {
                bind(event.tenantId(), null);
                mentionService.markFailed(event.mentionId(), e.getMessage());
            } catch (Exception ignored) {
                // best effort
            }
        } finally {
            snapshot.restore();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFailed(ProjectMentionTaskFailedEvent event) {
        if (event == null || event.mentionId() == null) {
            return;
        }
        ContextSnapshot snapshot = ContextSnapshot.capture();
        try {
            bind(event.tenantId(), null);
            PmRequirementCommentMention mention = mentionService.find(event.mentionId());
            if (mention == null
                    || CommentMentionStatus.PROCESSED.value().equals(mention.getStatus())) {
                return;
            }
            mentionService.markFailed(event.mentionId(), event.error());
        } catch (Exception e) {
            log.warn("Project mention failure record failed: mention={}, error={}",
                    event.mentionId(), e.getMessage());
        } finally {
            snapshot.restore();
        }
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

package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.dtvo.ProjectAssistantAvatarVO;
import com.buukle.agent.project.dtvo.ProjectAssistantInboxVO;
import com.buukle.agent.project.dtvo.ProjectAssistantSessionVO;
import com.buukle.agent.project.dtvo.ProjectAssistantSettingsVO;
import com.buukle.agent.project.dtvo.ProjectContextEventVO;
import com.buukle.agent.project.dtvo.ProjectMemberVO;
import com.buukle.agent.project.dtvo.enums.ProjectMemberRole;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.domain.PmProjectAssistantSession;
import com.buukle.agent.project.domain.PmProjectMember;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.repository.PmProjectMemberMapper;
import com.buukle.agent.project.spi.ProjectAssistantRunCommand;
import com.buukle.agent.project.spi.ProjectAssistantRunPort;
import com.buukle.agent.project.spi.ProjectAssistantRunResult;
import com.buukle.agent.project.spi.ProjectClarificationAnswerCommand;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.ProjectThreadSpi;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Project Assistant entry point.
 *
 * <p>Each project owns one canonical PROJECT_ROUTER session. A user conversation is a soft fork
 * of that session; both receive the shared project-context block, and every write appends to it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAssistantService {

    private static final String ROLE_MANAGER = ProjectMemberRole.MANAGER.value();
    private static final String CANONICAL_TITLE_PREFIX = "Project Assistant · ";
    private static final String FORK_KIND = "PROJECT_ASSISTANT";
    private static final String FORK_STATE_ACTIVE = "ACTIVE";
    private static final String AUTO_ANALYZE_PROMPT = """
            Analyze the project's recent changes in read-only mode:
            1. Use project_query to read the project overview, open/overdue/high-priority requirements, and recent comments.
            2. Identify risks, blockers, priority conflicts, and next-step suggestions.
            3. Use project_insight to save 1-3 insights (insightType=SUMMARY/RISK/PROGRESS, severity=INFO/WARN/HIGH).
            4. Do not modify any project or requirement data and do not ask humans any questions.
            """;

    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper memberMapper;
    private final ProjectService projectService;
    private final ProjectContextEventService contextEventService;
    private final ProjectInsightService insightService;
    private final ProjectAssistantSessionRegistry sessionRegistry;
    private final ProjectAssistantConfigService configService;
    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final ProjectThreadSpi projectThreadSpi;
    private final ClarificationSpi clarificationSpi;
    private final ProjectAssistantRunPort runPort;
    private final SuperAgentDirectorySpi directorySpi;
    private final UserSpi userSpi;

    // ------------------------------------------------------------------ session

    @Transactional
    public ProjectAssistantSessionVO ensureSession(Long projectId) {
        PmProject project = requireMember(projectId);
        Long tenantId = currentTenant();
        InstanceVO instance = instanceSpi.ensureProjectRouter(tenantId);
        if (instance == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "project assistant is not configured: no model route for this tenant");
        }
        String username = currentUsername();
        String ownerUsername = usernameOf(project.getOwnerUserId());
        Long threadId = projectThreadSpi.ensureProjectThread(
                tenantId, projectId, project.getName(), username);
        if (threadId == null) {
            throw new BizException(CommonErrorCode.INTERNAL_ERROR, "project thread unavailable");
        }

        SessionVO canonical = null;
        PmProjectAssistantSession canonicalLink = sessionRegistry.findCanonical(projectId);
        if (canonicalLink != null) {
            canonical = safeSharedSession(canonicalLink.getSessionId());
        }
        if (canonical == null) {
            canonical = sessionSpi.createSession(newSession(
                    project, tenantId, instance.getId(), threadId, ownerUsername,
                    CANONICAL_TITLE_PREFIX + project.getName(),
                    null));
        }
        sessionRegistry.register(tenantId, projectId, canonical.getId(), null, null,
                PmProjectAssistantSession.ROLE_CANONICAL);
        SessionVO fork = sessionSpi.findProjectForkSession(threadId, username, canonical.getId());
        if (fork == null) {
            fork = sessionSpi.createSession(newSession(
                    project, tenantId, instance.getId(), threadId, username,
                    CANONICAL_TITLE_PREFIX + project.getName() + " · " + displayNameOf(currentUser()),
                    canonical.getId()));
        }
        sessionRegistry.register(tenantId, projectId, fork.getId(), canonical.getId(), currentUser(),
                PmProjectAssistantSession.ROLE_FORK);

        ProjectAssistantSessionVO vo = new ProjectAssistantSessionVO();
        vo.setSessionId(fork.getId());
        vo.setCanonicalSessionId(canonical.getId());
        vo.setThreadId(threadId);
        vo.setInstanceId(instance.getId());
        vo.setProjectId(projectId);
        return vo;
    }

    public ProjectAssistantRunResult triggerAnalysis(Long projectId, Long requirementId) {
        ProjectAssistantSessionVO session = ensureSession(projectId);
        ProjectAssistantRunCommand command = new ProjectAssistantRunCommand(
                currentTenant(), projectId, currentUser(), currentUsername(), session.getSessionId(),
                requirementId, AUTO_ANALYZE_PROMPT, RunMode.READ_ONLY.name(), true,
                ProjectAssistantConstants.SOURCE_MANUAL, null);
        return runPort.runFork(command);
    }

    // ------------------------------------------------------------------ inbox

    public List<ProjectAssistantInboxVO> inbox() {
        Long tenantId = currentTenant();
        Long userId = currentUser();
        List<ClarificationVO> pending = clarificationSpi.listPendingByTargetUser(tenantId, userId);
        if (pending.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<ProjectAssistantInboxVO> result = new ArrayList<>();
        for (ClarificationVO clarification : pending) {
            if (isExpired(clarification, now)) {
                continue;
            }
            SessionVO session = safeSession(clarification.getSessionId());
            if (session == null) {
                continue;
            }
            PmProjectAssistantSession link = sessionRegistry.findBySessionId(session.getId());
            if (link == null) {
                continue;
            }
            Long projectId = link.getProjectId();
            PmProject project = projectMapper.selectById(projectId);
            if (project == null) {
                continue;
            }
            Long requesterId = link.getRequesterUserId();
            ProjectAssistantInboxVO vo = new ProjectAssistantInboxVO();
            vo.setClarificationId(clarification.getClarificationId());
            vo.setRunId(clarification.getRunId());
            vo.setSessionId(clarification.getSessionId());
            vo.setProjectId(projectId);
            vo.setProjectName(project.getName());
            vo.setTitle(clarification.getTitle());
            vo.setType(clarification.getType());
            vo.setMultiple(clarification.getMultiple());
            vo.setOptions(clarification.getOptions());
            vo.setDescription(clarification.getDescription());
            vo.setRequesterUserId(requesterId);
            vo.setRequesterName(requesterId == null ? null : displayNameOf(requesterId));
            vo.setExpiresAt(clarification.getExpiresAt());
            vo.setCreatedAt(clarification.getCreatedAt());
            result.add(vo);
        }
        return result;
    }

    public void answer(String clarificationId, String response) {
        ClarificationVO pending = clarificationSpi.findPendingByClarificationId(clarificationId);
        if (pending == null) {
            // Idempotent: another tab already answered, or the question expired.
            return;
        }
        if (!Objects.equals(currentUser(), pending.getTargetUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN,
                    "this question is addressed to another project member");
        }
        runPort.answerClarification(new ProjectClarificationAnswerCommand(
                clarificationId, currentUser(), currentUsername(), response));
    }

    // ------------------------------------------------------------------ settings / context / avatars

    public ProjectAssistantSettingsVO settings(Long projectId) {
        PmProject project = requireMember(projectId);
        ProjectAssistantSettingsVO vo = new ProjectAssistantSettingsVO();
        vo.setProjectId(projectId);
        vo.setAutoAnalyze(configService.isAutoAnalyze(project.getId()));
        return vo;
    }

    @Transactional
    public void updateSettings(Long projectId, Boolean autoAnalyze) {
        PmProject project = requireManager(projectId);
        if (autoAnalyze == null) {
            return;
        }
        configService.setAutoAnalyze(currentTenant(), project.getId(), autoAnalyze);
    }

    public List<ProjectContextEventVO> context(Long projectId, int limit) {
        requireMember(projectId);
        return contextEventService.recent(projectId, limit);
    }

    public List<ProjectAssistantAvatarVO> avatars(Long projectId) {
        requireMember(projectId);
        Long userId = currentUser();
        Map<Long, ChatAgentIdentity> byId = new LinkedHashMap<>();
        for (ChatAgentIdentity identity : directorySpi.listActiveOwnedBy(userId)) {
            byId.put(identity.agentId(), identity);
        }
        List<Long> memberIds = projectService.members(projectId).stream()
                .map(ProjectMemberVO::getUserId)
                .filter(Objects::nonNull)
                .toList();
        for (ChatAgentIdentity identity : directorySpi.listPublicByOwners(memberIds)) {
            byId.putIfAbsent(identity.agentId(), identity);
        }
        Map<Long, UserIdentityVO> identities = identities(byId.values().stream()
                .map(ChatAgentIdentity::ownerUserId)
                .collect(Collectors.toSet()));
        List<ProjectAssistantAvatarVO> result = new ArrayList<>(byId.size());
        for (ChatAgentIdentity identity : byId.values()) {
            ProjectAssistantAvatarVO vo = new ProjectAssistantAvatarVO();
            vo.setAgentId(identity.agentId());
            vo.setName(identity.name());
            vo.setOwnerUserId(identity.ownerUserId());
            vo.setOwnerName(displayName(identity.ownerUserId(), identities));
            var avatar = directorySpi.findAvatar(identity.agentId());
            vo.setPublicVisible(avatar != null && avatar.publicVisible());
            vo.setOwnedByRequester(Objects.equals(userId, identity.ownerUserId()));
            vo.setStatus(identity.status());
            result.add(vo);
        }
        return result;
    }

    public ProjectInsightService insights() {
        return insightService;
    }    // ------------------------------------------------------------------ helpers

    private CreateSessionDTO newSession(PmProject project, Long tenantId, Long instanceId,
                                        Long threadId, String createdBy, String title,
                                        Long parentSessionId) {
        CreateSessionDTO dto = new CreateSessionDTO();
        dto.setAgentInstanceId(instanceId);
        dto.setTitle(title);
        dto.setSessionType(SessionType.PROJECT_ROUTER);
        dto.setThreadId(threadId);
        dto.setCreatedBy(createdBy);
        dto.setParentSessionId(parentSessionId);
        if (parentSessionId != null) {
            dto.setForkKind(FORK_KIND);
            dto.setForkState(FORK_STATE_ACTIVE);
        }
        return dto;
    }

    private PmProject requireMember(Long projectId) {
        return requireRole(projectId, ProjectMemberRole.VIEWER.value());
    }

    private PmProject requireManager(Long projectId) {
        return requireRole(projectId, ROLE_MANAGER);
    }

    private PmProject requireRole(Long projectId, String minimumRole) {
        PmProject project = projectMapper.selectById(projectId);
        if (project == null || !currentTenant().equals(project.getTenantId())) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "project not found");
        }
        Long userId = currentUser();
        if (Objects.equals(project.getOwnerUserId(), userId)) {
            return project;
        }
        PmProjectMember member = memberMapper.selectOne(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .eq(PmProjectMember::getUserId, userId));
        if (member == null || ProjectMemberRole.rankOf(member.getMemberRole()) < roleRank(minimumRole)) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "project permission denied");
        }
        return project;
    }

    private static int roleRank(String role) {
        return ProjectMemberRole.rankOf(role);
    }

    private SessionVO safeSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return sessionSpi.getSessionShared(sessionId);
        } catch (Exception e) {
            return null;
        }
    }

    private SessionVO safeSharedSession(Long sessionId) {
        return safeSession(sessionId);
    }

    private static boolean isExpired(ClarificationVO clarification, LocalDateTime now) {
        String expiresAt = clarification.getExpiresAt();
        if (expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        try {
            return LocalDateTime.parse(expiresAt.replace(' ', 'T')).isBefore(now);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<Long, UserIdentityVO> identities(Set<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : userSpi.identityMap(ids);
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return "system";
        }
        UserIdentityVO identity = identities(Set.of(userId)).get(userId);
        if (identity == null) {
            return "system";
        }
        return StringUtils.hasText(identity.getUsername())
                ? identity.getUsername() : String.valueOf(userId);
    }

    private String displayNameOf(Long userId) {
        if (userId == null) {
            return "Project member";
        }
        UserIdentityVO identity = identities(Set.of(userId)).get(userId);
        if (identity == null) {
            return "Project member";
        }
        if (StringUtils.hasText(identity.getDisplayName())) {
            return identity.getDisplayName();
        }
        if (StringUtils.hasText(identity.getEnglishName())) {
            return identity.getEnglishName();
        }
        return StringUtils.hasText(identity.getUsername()) ? identity.getUsername() : "Project member";
    }

    private String displayName(Long userId, Map<Long, UserIdentityVO> identities) {
        if (userId == null) {
            return null;
        }
        UserIdentityVO identity = identities.get(userId);
        if (identity == null) {
            return null;
        }
        if (StringUtils.hasText(identity.getDisplayName())) {
            return identity.getDisplayName();
        }
        if (StringUtils.hasText(identity.getEnglishName())) {
            return identity.getEnglishName();
        }
        return identity.getUsername();
    }

    private Long currentTenant() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? 0L : tenantId;
    }

    private Long currentUser() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String currentUsername() {
        String username = AuthContext.getUsername();
        if (StringUtils.hasText(username)) {
            return username;
        }
        return usernameOf(currentUser());
    }
}

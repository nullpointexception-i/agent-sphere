package com.buukle.agent.project.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.domain.PmProjectMember;
import com.buukle.agent.project.domain.PmRequirement;
import com.buukle.agent.project.domain.PmRequirementActivity;
import com.buukle.agent.project.domain.PmRequirementComment;
import com.buukle.agent.project.dtvo.AddProjectMemberDTO;
import com.buukle.agent.project.dtvo.CreateRequirementCommentDTO;
import com.buukle.agent.project.dtvo.CreateRequirementDTO;
import com.buukle.agent.project.dtvo.ProjectAssistantAvatarVO;
import com.buukle.agent.project.dtvo.ProjectContextEventVO;
import com.buukle.agent.project.dtvo.ProjectMemberVO;
import com.buukle.agent.project.dtvo.ProjectVO;
import com.buukle.agent.project.dtvo.RequirementActivityVO;
import com.buukle.agent.project.dtvo.RequirementCommentVO;
import com.buukle.agent.project.dtvo.RequirementVO;
import com.buukle.agent.project.dtvo.UpdateProjectDTO;
import com.buukle.agent.project.dtvo.UpdateRequirementDTO;
import com.buukle.agent.project.dtvo.enums.ProjectContextEventType;
import com.buukle.agent.project.dtvo.enums.CommentAuthorType;
import com.buukle.agent.project.dtvo.enums.ProjectMemberRole;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.repository.PmProjectMemberMapper;
import com.buukle.agent.project.repository.PmRequirementActivityMapper;
import com.buukle.agent.project.repository.PmRequirementCommentMapper;
import com.buukle.agent.project.repository.PmRequirementMapper;
import com.buukle.agent.project.service.ProjectAssistantSessionRegistry;
import com.buukle.agent.project.service.ProjectContextEventService;
import com.buukle.agent.project.service.ProjectService;
import com.buukle.agent.project.service.RequirementCommentService;
import com.buukle.agent.superagent.spi.AvatarConsultCommand;
import com.buukle.agent.superagent.spi.AvatarConsultResult;
import com.buukle.agent.superagent.spi.AvatarConsultSpi;
import com.buukle.agent.superagent.spi.AvatarInfo;
import com.buukle.agent.superagent.spi.ChatAgentIdentity;
import com.buukle.agent.superagent.spi.EntityTypes;
import com.buukle.agent.superagent.spi.SuperAgentDirectorySpi;
import com.buukle.agent.superagent.spi.WorkItemCreateCommand;
import com.buukle.agent.superagent.spi.WorkItemDispatchSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Shared implementation of the four project-assistant tools. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectToolService {

    private static final String ROLE_OWNER = com.buukle.agent.project.dtvo.enums.ProjectMemberRole.OWNER.value();
    private static final String ROLE_MANAGER = com.buukle.agent.project.dtvo.enums.ProjectMemberRole.MANAGER.value();
    private static final String SESSION_TYPE = SessionType.PROJECT_ROUTER;
    private static final String STATUS_ACTIVE = ProjectAssistantConstants.STATUS_ACTIVE;
    private static final long CONSULT_TIMEOUT_SECONDS = 25;
    private static final int CONTEXT_SUMMARY_LIMIT = 300;

    private final SessionSpi sessionSpi;
    private final ClarificationSpi clarificationSpi;
    private final UserSpi userSpi;
    private final ProjectService projectService;
    private final ProjectContextEventService contextEventService;
    private final ProjectAssistantSessionRegistry sessionRegistry;
    private final RequirementCommentService commentService;
    private final SuperAgentDirectorySpi directorySpi;
    private final WorkItemDispatchSpi workItemDispatchSpi;
    /**
     * Lazy: avatar consultation needs ChatRuntimeService, which needs the builtin tool registry,
     * which needs this tool service. A lazy proxy breaks that construction cycle.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AvatarConsultSpi avatarConsultSpi;
    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper memberMapper;
    private final PmRequirementMapper requirementMapper;
    private final PmRequirementCommentMapper commentMapper;
    private final PmRequirementActivityMapper activityMapper;

    // ------------------------------------------------------------------ context

    public ToolSession resolve(ProjectToolContext input) {
        SessionVO session = sessionSpi.getSessionShared(input.getSessionId());
        if (session == null || !SESSION_TYPE.equals(session.getSessionType())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "not a project assistant session");
        }
        var link = sessionRegistry.findBySessionId(session.getId());
        if (link == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "project context is missing from this session");
        }
        Long projectId = link.getProjectId();
        Long requester = link.getRequesterUserId();
        if (requester == null) {
            requester = AuthContext.getUserId();
        }
        if (requester == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        Long tenantId = session.getTenantId();
        PmProject project = loadProjectShared(projectId);
        if (project == null || !Objects.equals(project.getTenantId(), tenantId)) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "project not found");
        }
        String username = usernameOf(requester);
        // The async runner may not carry the request tenant scope; establish the project actor
        // explicitly so every downstream project query/write sees the right tenant and user.
        AuthContext.setUserId(requester);
        AuthContext.setUsername(username);
        AuthContext.setSuperAdmin(false);
        AuthContext.setPermissions(java.util.Set.of());
        TenantContext.set(tenantId);
        TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, username));
        return new ToolSession(session, project, tenantId, projectId, requester,
                link.getCanonicalSessionId(), input.getRunId());
    }

    // ------------------------------------------------------------------ query

    public ProjectToolResult query(ProjectToolContext input) {
        ToolSession ctx = resolve(input);
        requireMember(ctx);
        String operation = op(input, "project");
        switch (operation) {
            case "project" -> {
                return projectOverview(ctx);
            }
            case "requirements" -> {
                var page = projectService.requirementPage(ctx.projectId(), input.getStatus(),
                        input.getKeyword(), page(input), pageSize(input));
                return ProjectToolResult.ok("requirements loaded", page);
            }
            case "requirement" -> {
                return requirementDetail(ctx, input.getRequirementId());
            }
            case "members" -> {
                return ProjectToolResult.ok("members loaded",
                        projectService.members(ctx.projectId()));
            }
            case "context" -> {
                List<ProjectContextEventVO> events =
                        contextEventService.recent(ctx.projectId(), limit(input));
                return ProjectToolResult.ok("context loaded", events);
            }
            case "avatars" -> {
                return ProjectToolResult.ok("avatars loaded", listAvatars(ctx));
            }
            case "workItems" -> {
                return ProjectToolResult.ok("work items are reported through the project context",
                        contextEventService.recent(ctx.projectId(), limit(input)));
            }
            default -> {
                return ProjectToolResult.failed("unsupported query operation: " + operation);
            }
        }
    }

    private ProjectToolResult projectOverview(ToolSession ctx) {
        ProjectVO project = projectService.detail(ctx.projectId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project", project);
        data.put("members", projectService.members(ctx.projectId()));
        var requirements = projectService.requirementPage(
                ctx.projectId(), null, null, 1, 100);
        data.put("requirementTotal", requirements.getTotal());
        data.put("requirements", requirements.getRecords());
        data.put("recentContext", contextEventService.recent(ctx.projectId(), 20));
        return ProjectToolResult.ok("project overview loaded", data);
    }

    private ProjectToolResult requirementDetail(ToolSession ctx, Long requirementId) {
        if (requirementId == null) {
            return ProjectToolResult.failed("requirementId is required");
        }
        PmRequirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !Objects.equals(requirement.getProjectId(), ctx.projectId())) {
            return ProjectToolResult.failed("requirement not found in this project");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requirement", projectService.requirement(requirementId));
        data.put("comments", projectService.requirementComments(requirementId));
        data.put("activities", projectService.activities(requirementId));
        return ProjectToolResult.ok("requirement loaded", data);
    }

    // ------------------------------------------------------------------ mutate

    public ProjectToolResult mutate(ProjectToolContext input) {
        ToolSession ctx = resolve(input);
        String operation = op(input, "");
        try {
            switch (operation) {
                case "createRequirement" -> {
                    requireMember(ctx);
                    return createRequirement(ctx, input);
                }
                case "updateRequirement" -> {
                    requireMember(ctx);
                    return updateRequirement(ctx, input);
                }
                case "changeRequirementStatus" -> {
                    requireMember(ctx);
                    return changeRequirementStatus(ctx, input);
                }
                case "commentRequirement" -> {
                    requireMember(ctx);
                    return commentRequirement(ctx, input);
                }
                case "updateProject" -> {
                    requireManager(ctx);
                    return updateProject(ctx, input);
                }
                case "addMember" -> {
                    requireManager(ctx);
                    return addMember(ctx, input);
                }
                case "removeMember" -> {
                    requireManager(ctx);
                    String denial = requireConfirmation(ctx, input);
                    if (denial != null) return ProjectToolResult.failed(denial);
                    return removeMember(ctx, input);
                }
                case "archiveProject" -> {
                    requireOwner(ctx);
                    String denial = requireConfirmation(ctx, input);
                    if (denial != null) return ProjectToolResult.failed(denial);
                    projectService.archive(ctx.projectId());
                    record(ctx, null, ProjectContextEventType.PROJECT.value(),
                            "Project archived by " + actorName(ctx));
                    return ProjectToolResult.ok("project archived", ctx.projectId());
                }
                case "deleteProject" -> {
                    requireOwner(ctx);
                    String denial = requireConfirmation(ctx, input);
                    if (denial != null) return ProjectToolResult.failed(denial);
                    projectService.delete(ctx.projectId());
                    return ProjectToolResult.ok("project deleted", ctx.projectId());
                }
                case "deleteRequirement" -> {
                    requireManager(ctx);
                    String denial = requireConfirmation(ctx, input);
                    if (denial != null) return ProjectToolResult.failed(denial);
                    return deleteRequirement(ctx, input);
                }
                default -> {
                    return ProjectToolResult.failed("unsupported mutate operation: " + operation);
                }
            }
        } catch (BizException e) {
            return ProjectToolResult.failed(e.getMessage());
        }
    }

    private ProjectToolResult createRequirement(ToolSession ctx, ProjectToolContext input) {
        if (!StringUtils.hasText(input.getTitle())) {
            return ProjectToolResult.failed("title is required");
        }
        CreateRequirementDTO dto = new CreateRequirementDTO();
        dto.setTitle(input.getTitle());
        dto.setDescription(input.getDescription());
        dto.setPriority(input.getPriority());
        dto.setAssigneeUserId(input.getAssigneeUserId());
        dto.setStartDate(date(input.getStartDate()));
        dto.setDueDate(date(input.getDueDate()));
        Long id = projectService.createRequirement(ctx.projectId(), dto);
        PmRequirement requirement = requirementMapper.selectById(id);
        String key = requirement == null ? String.valueOf(id) : requirement.getRequirementKey();
        record(ctx, id, ProjectContextEventType.REQUIREMENT.value(),
                "Requirement " + key + " \"" + input.getTitle() + "\" created by " + actorName(ctx));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requirementId", id);
        data.put("requirementKey", key);
        return ProjectToolResult.ok("requirement created", data);
    }

    private ProjectToolResult updateRequirement(ToolSession ctx, ProjectToolContext input) {
        Long requirementId = input.getRequirementId();
        if (requirementId == null) {
            return ProjectToolResult.failed("requirementId is required");
        }
        PmRequirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !Objects.equals(requirement.getProjectId(), ctx.projectId())) {
            return ProjectToolResult.failed("requirement not found in this project");
        }
        UpdateRequirementDTO dto = new UpdateRequirementDTO();
        dto.setTitle(input.getTitle());
        dto.setDescription(input.getDescription());
        dto.setPriority(input.getPriority());
        dto.setAssigneeUserId(input.getAssigneeUserId());
        dto.setStartDate(date(input.getStartDate()));
        dto.setDueDate(date(input.getDueDate()));
        projectService.updateRequirement(requirementId, dto);
        record(ctx, requirementId, ProjectContextEventType.REQUIREMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " updated by " + actorName(ctx));
        return ProjectToolResult.ok("requirement updated", requirementId);
    }

    private ProjectToolResult changeRequirementStatus(ToolSession ctx, ProjectToolContext input) {
        Long requirementId = input.getRequirementId();
        if (requirementId == null || !StringUtils.hasText(input.getStatus())) {
            return ProjectToolResult.failed("requirementId and status are required");
        }
        PmRequirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !Objects.equals(requirement.getProjectId(), ctx.projectId())) {
            return ProjectToolResult.failed("requirement not found in this project");
        }
        String from = requirement.getStatus();
        projectService.changeRequirementStatus(requirementId, input.getStatus());
        record(ctx, requirementId, ProjectContextEventType.REQUIREMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " status " + from + " -> "
                        + input.getStatus() + " by " + actorName(ctx));
        return ProjectToolResult.ok("requirement status changed", requirementId);
    }

    private ProjectToolResult commentRequirement(ToolSession ctx, ProjectToolContext input) {
        Long requirementId = input.getRequirementId();
        if (requirementId == null || !StringUtils.hasText(input.getContent())) {
            return ProjectToolResult.failed("requirementId and content are required");
        }
        PmRequirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !Objects.equals(requirement.getProjectId(), ctx.projectId())) {
            return ProjectToolResult.failed("requirement not found in this project");
        }
        CreateRequirementCommentDTO dto = new CreateRequirementCommentDTO();
        dto.setContent(input.getContent());
        RequirementCommentVO comment = projectService.addRequirementComment(requirementId, dto);
        record(ctx, requirementId, ProjectContextEventType.COMMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " comment: "
                        + preview(input.getContent()) + " by " + actorName(ctx));
        return ProjectToolResult.ok("comment added", comment);
    }

    private ProjectToolResult updateProject(ToolSession ctx, ProjectToolContext input) {
        UpdateProjectDTO dto = new UpdateProjectDTO();
        dto.setName(input.getTitle());
        dto.setDescription(input.getDescription());
        dto.setStartDate(date(input.getStartDate()));
        dto.setEndDate(date(input.getDueDate()));
        projectService.update(ctx.projectId(), dto);
        record(ctx, null, ProjectContextEventType.PROJECT.value(),
                "Project updated by " + actorName(ctx));
        return ProjectToolResult.ok("project updated", ctx.projectId());
    }

    private ProjectToolResult addMember(ToolSession ctx, ProjectToolContext input) {
        if (input.getUserId() == null) {
            return ProjectToolResult.failed("userId is required");
        }
        AddProjectMemberDTO dto = new AddProjectMemberDTO();
        dto.setUserId(input.getUserId());
        dto.setMemberRole(StringUtils.hasText(input.getMemberRole())
                ? input.getMemberRole() : ProjectMemberRole.MEMBER.value());
        projectService.addMember(ctx.projectId(), dto);
        record(ctx, null, ProjectContextEventType.MEMBER.value(),
                "Member added: " + displayName(input.getUserId()) + " by " + actorName(ctx));
        return ProjectToolResult.ok("member added", input.getUserId());
    }

    private ProjectToolResult removeMember(ToolSession ctx, ProjectToolContext input) {
        if (input.getUserId() == null) {
            return ProjectToolResult.failed("userId is required");
        }
        projectService.removeMember(ctx.projectId(), input.getUserId());
        record(ctx, null, ProjectContextEventType.MEMBER.value(),
                "Member removed: " + displayName(input.getUserId()) + " by " + actorName(ctx));
        return ProjectToolResult.ok("member removed", input.getUserId());
    }

    private ProjectToolResult deleteRequirement(ToolSession ctx, ProjectToolContext input) {
        Long requirementId = input.getRequirementId();
        if (requirementId == null) {
            return ProjectToolResult.failed("requirementId is required");
        }
        PmRequirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !Objects.equals(requirement.getProjectId(), ctx.projectId())) {
            return ProjectToolResult.failed("requirement not found in this project");
        }
        projectService.deleteRequirement(requirementId);
        record(ctx, null, ProjectContextEventType.REQUIREMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " deleted by " + actorName(ctx));
        return ProjectToolResult.ok("requirement deleted", requirementId);
    }

    // ------------------------------------------------------------------ comment

    /** Create a requirement comment authored by the Project Assistant. */
    public ProjectToolResult comment(ProjectToolContext input) {
        ToolSession ctx = resolve(input);
        requireMember(ctx);
        Long requirementId = input.getRequirementId();
        if (requirementId == null || !StringUtils.hasText(input.getContent())) {
            return ProjectToolResult.failed("requirementId and content are required");
        }
        PmRequirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !Objects.equals(requirement.getProjectId(), ctx.projectId())) {
            return ProjectToolResult.failed("requirement not found in this project");
        }
        if (ctx.runId() != null) {
            long used = commentMapper.selectCount(new LambdaQueryWrapper<PmRequirementComment>()
                    .eq(PmRequirementComment::getSourceRunId, ctx.runId()));
            if (used >= ProjectAssistantConstants.MAX_COMMENTS_PER_RUN) {
                return ProjectToolResult.failed("comment limit reached for this analysis run");
            }
        }
        String actionKey = StringUtils.hasText(input.getActionKey())
                ? input.getActionKey()
                : "comment:" + requirementId + ":" + hash(input.getContent());
        if (actionKey.length() > 128) {
            actionKey = actionKey.substring(0, 128);
        }
        RequirementCommentVO comment = commentService.add(
                new RequirementCommentService.RequirementCommentCommand(
                        ctx.tenantId(), ctx.projectId(), requirementId, ctx.requesterUserId(),
                        CommentAuthorType.ASSISTANT.value(), ProjectAssistantConstants.AUTHOR_NAME,
                        input.getParentCommentId(), input.getContent(), null, input.getMentions(),
                        ctx.session().getId(), ctx.runId(), null, actionKey));
        return ProjectToolResult.ok("comment added", comment);
    }

    private static String hash(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    // ------------------------------------------------------------------ avatar

    public ProjectToolResult avatar(ProjectToolContext input) {
        ToolSession ctx = resolve(input);
        requireMember(ctx);
        String operation = op(input, "list");
        if ("list".equals(operation)) {
            return ProjectToolResult.ok("avatars loaded", listAvatars(ctx));
        }
        if (input.getAgentId() == null) {
            return ProjectToolResult.failed("agentId is required");
        }
        AvatarInfo avatar = directorySpi.findAvatar(input.getAgentId());
        if (avatar == null) {
            return ProjectToolResult.failed("avatar not found");
        }
        String denial = avatarAccessDenial(ctx, avatar);
        if (denial != null) {
            return ProjectToolResult.failed(denial);
        }
        if ("consult".equals(operation)) {
            if (!StringUtils.hasText(input.getQuestion())) {
                return ProjectToolResult.failed("question is required");
            }
            AvatarConsultResult result = avatarConsultSpi.consult(new AvatarConsultCommand(
                    ctx.tenantId(), ctx.requesterUserId(), usernameOf(ctx.requesterUserId()),
                    avatar.agentId(), input.getQuestion(), CONSULT_TIMEOUT_SECONDS));
            if (!result.ok()) {
                return ProjectToolResult.failed(result.error());
            }
            record(ctx, input.getRequirementId(), ProjectContextEventType.ASSISTANT.value(),
                    "Consulted avatar \"" + avatar.name() + "\": " + preview(input.getQuestion())
                            + " -> " + preview(result.answer()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("agentId", avatar.agentId());
            data.put("answer", result.answer());
            return ProjectToolResult.ok("avatar consulted", data);
        }
        if ("dispatch".equals(operation)) {
            if (!StringUtils.hasText(input.getGoal())) {
                return ProjectToolResult.failed("goal is required");
            }
            Long workItemId = workItemDispatchSpi.dispatch(new WorkItemCreateCommand(
                    ctx.tenantId(), avatar.ownerUserId(), avatar.agentId(),
                    ctx.session().getThreadId(), input.getGoal(), input.getGoal(),
                    input.getTaskPriority() == null ? 100 : input.getTaskPriority(),
                    RunMode.WRITE.name(), ProjectAssistantConstants.REQUEST_SOURCE, sourceRef(ctx, input.getRequirementId()),
                    null, input.getRequirementId() == null ? EntityTypes.PROJECT : EntityTypes.REQUIREMENT,
                    input.getRequirementId() == null ? ctx.projectId() : input.getRequirementId(),
                    null));
            record(ctx, input.getRequirementId(), ProjectContextEventType.ASSISTANT.value(),
                    "Dispatched a task to avatar \"" + avatar.name() + "\": " + preview(input.getGoal()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("agentId", avatar.agentId());
            data.put("dispatchedWorkItemId", workItemId);
            return ProjectToolResult.ok("task dispatched", data);
        }
        return ProjectToolResult.failed("unsupported avatar operation: " + operation);
    }

    private List<ProjectAssistantAvatarVO> listAvatars(ToolSession ctx) {
        Map<Long, ChatAgentIdentity> byId = new LinkedHashMap<>();
        for (ChatAgentIdentity identity : directorySpi.listActiveOwnedBy(ctx.requesterUserId())) {
            byId.put(identity.agentId(), identity);
        }
        List<Long> memberIds = projectService.members(ctx.projectId()).stream()
                .map(ProjectMemberVO::getUserId).filter(Objects::nonNull).toList();
        for (ChatAgentIdentity identity : directorySpi.listPublicByOwners(memberIds)) {
            byId.putIfAbsent(identity.agentId(), identity);
        }
        List<ProjectAssistantAvatarVO> result = new ArrayList<>();
        for (ChatAgentIdentity identity : byId.values()) {
            ProjectAssistantAvatarVO vo = new ProjectAssistantAvatarVO();
            vo.setAgentId(identity.agentId());
            vo.setName(identity.name());
            vo.setOwnerUserId(identity.ownerUserId());
            vo.setOwnerName(displayName(identity.ownerUserId()));
            AvatarInfo info = directorySpi.findAvatar(identity.agentId());
            vo.setPublicVisible(info != null && info.publicVisible());
            vo.setOwnedByRequester(Objects.equals(ctx.requesterUserId(), identity.ownerUserId()));
            vo.setStatus(identity.status());
            result.add(vo);
        }
        return result;
    }

    private String avatarAccessDenial(ToolSession ctx, AvatarInfo avatar) {
        if (!STATUS_ACTIVE.equals(avatar.status())) {
            return "avatar is not active";
        }
        if (Objects.equals(ctx.requesterUserId(), avatar.ownerUserId())) {
            return null;
        }
        if (!avatar.publicVisible()) {
            return "avatar is private";
        }
        if (!isMember(ctx.projectId(), avatar.ownerUserId())) {
            return "avatar owner is not a project member";
        }
        if (roleRank(roleOf(ctx.projectId(), ctx.requesterUserId())) < roleRank(ROLE_MANAGER)) {
            return "only project managers may use another member's avatar";
        }
        return null;
    }

    // ------------------------------------------------------------------ permissions

    private void requireMember(ToolSession ctx) {
        if (!isMember(ctx.projectId(), ctx.requesterUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "project permission denied");
        }
    }

    private void requireManager(ToolSession ctx) {
        if (roleRank(roleOf(ctx.projectId(), ctx.requesterUserId())) < roleRank(ROLE_MANAGER)) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "project manager permission required");
        }
    }

    private void requireOwner(ToolSession ctx) {
        if (roleRank(roleOf(ctx.projectId(), ctx.requesterUserId())) < roleRank(ROLE_OWNER)) {
            throw new BizException(CommonErrorCode.FORBIDDEN, "project owner permission required");
        }
    }

    private boolean isMember(Long projectId, Long userId) {
        return roleRank(roleOf(projectId, userId)) > 0;
    }

    private String roleOf(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return null;
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            return null;
        }
        if (Objects.equals(project.getOwnerUserId(), userId)) {
            return ROLE_OWNER;
        }
        PmProjectMember member = memberMapper.selectOne(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .eq(PmProjectMember::getUserId, userId));
        return member == null ? null : member.getMemberRole();
    }

    private static int roleRank(String role) {
        return com.buukle.agent.project.dtvo.enums.ProjectMemberRole.rankOf(role);
    }

    private String requireConfirmation(ToolSession ctx, ProjectToolContext input) {
        if (!StringUtils.hasText(input.getConfirmationId())) {
            return "destructive operation requires a confirmationId from ask_clarification";
        }
        ClarificationVO pending = clarificationSpi.findByClarificationId(input.getConfirmationId());
        if (pending == null) {
            return "confirmation not found";
        }
        if (!Objects.equals(pending.getSessionId(), ctx.session().getId())) {
            return "confirmation does not belong to this conversation";
        }
        if (ctx.runId() != null && pending.getRunId() != null
                && !Objects.equals(pending.getRunId(), ctx.runId())) {
            return "confirmation does not belong to this run";
        }
        if (!"responded".equals(pending.getStatus())) {
            return "confirmation is not answered";
        }
        if (!affirmative(pending.getUserResponse())) {
            return "the user declined this operation";
        }
        return null;
    }

    private static boolean affirmative(String response) {
        if (response == null) {
            return false;
        }
        String value = response.toLowerCase();
        if (value.contains("no") || value.contains("reject") || value.contains("decline")) {
            return false;
        }
        return value.contains("yes") || value.contains("confirm") || value.contains("ok")
                || value.contains("allow") || value.contains("approve");
    }

    // ------------------------------------------------------------------ helpers

    private void record(ToolSession ctx, Long requirementId, String type, String summary) {
        contextEventService.record(ctx.projectId(), requirementId, type, summary,
                ctx.session().getId(), ctx.canonicalSessionId(), ctx.requesterUserId());
    }

    private String projectName(ToolSession ctx) {
        PmProject project = projectMapper.selectById(ctx.projectId());
        return project == null ? String.valueOf(ctx.projectId()) : project.getName();
    }

    private String actorName(ToolSession ctx) {
        return displayName(ctx.requesterUserId());
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
        return StringUtils.hasText(identity.getUsername()) ? identity.getUsername() : "Project member";
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return "system";
        }
        UserIdentityVO identity = userSpi.identityMap(Set.of(userId)).get(userId);
        return identity == null || !StringUtils.hasText(identity.getUsername())
                ? String.valueOf(userId) : identity.getUsername();
    }

    private static String sourceRef(ToolSession ctx, Long requirementId) {
        return requirementId == null
                ? ProjectAssistantConstants.THREAD_EXTERNAL_PREFIX + ctx.projectId()
                : ProjectAssistantConstants.THREAD_EXTERNAL_PREFIX + ctx.projectId()
                + ":requirement:" + requirementId;
    }

    /** Tenant-owned project rows may be hidden by an empty/self-only async scope. */
    private PmProject loadProjectShared(Long projectId) {
        TenantScope previous = TenantScopeHolder.get();
        try {
            TenantScopeHolder.clear();
            return projectMapper.selectById(projectId);
        } finally {
            if (previous == null) {
                TenantScopeHolder.clear();
            } else {
                TenantScopeHolder.start(previous);
            }
        }
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").strip();
        return text.length() <= CONTEXT_SUMMARY_LIMIT
                ? text : text.substring(0, CONTEXT_SUMMARY_LIMIT);
    }

    private static String op(ProjectToolContext input, String fallback) {
        return StringUtils.hasText(input.getOperation()) ? input.getOperation() : fallback;
    }

    private static int page(ProjectToolContext input) {
        return input.getPage() == null || input.getPage() < 1 ? 1 : input.getPage();
    }

    private static int pageSize(ProjectToolContext input) {
        int size = input.getPageSize() == null ? 20 : input.getPageSize();
        return Math.min(Math.max(size, 1), 100);
    }

    private static int limit(ProjectToolContext input) {
        int size = input.getPageSize() == null ? 20 : input.getPageSize();
        return Math.min(Math.max(size, 1), 100);
    }

    private static LocalDate date(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return LocalDate.parse(value.trim());
    }

    /** Resolved session/project identity for one tool call. */
    public record ToolSession(
            SessionVO session,
            PmProject project,
            Long tenantId,
            Long projectId,
            Long requesterUserId,
            Long canonicalSessionId,
            Long runId
    ) {
    }
}


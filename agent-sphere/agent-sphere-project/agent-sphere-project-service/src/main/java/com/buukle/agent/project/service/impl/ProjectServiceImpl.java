package com.buukle.agent.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.instance.dtvo.vo.UserIdentityVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.project.event.ProjectRequirementChangedEvent;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.domain.PmProjectMember;
import com.buukle.agent.project.domain.PmRequirement;
import com.buukle.agent.project.domain.PmRequirementActivity;
import com.buukle.agent.project.domain.PmRequirementComment;
import com.buukle.agent.project.dtvo.AddProjectMemberDTO;
import com.buukle.agent.project.dtvo.CreateProjectDTO;
import com.buukle.agent.project.dtvo.CreateRequirementCommentDTO;
import com.buukle.agent.project.dtvo.CreateRequirementDTO;
import com.buukle.agent.project.dtvo.ProjectMemberVO;
import com.buukle.agent.project.dtvo.ProjectVO;
import com.buukle.agent.project.dtvo.RequirementActivityVO;
import com.buukle.agent.project.dtvo.RequirementAttachmentVO;
import com.buukle.agent.project.dtvo.RequirementCommentVO;
import com.buukle.agent.project.dtvo.RequirementVO;
import com.buukle.agent.project.dtvo.UpdateProjectDTO;
import com.buukle.agent.project.dtvo.UpdateRequirementDTO;
import com.buukle.agent.project.dtvo.enums.ProjectContextEventType;
import com.buukle.agent.project.dtvo.enums.ProjectMemberRole;
import com.buukle.agent.project.dtvo.enums.ProjectStatus;
import com.buukle.agent.project.dtvo.enums.RequirementActivityType;
import com.buukle.agent.project.dtvo.enums.RequirementStatus;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.repository.PmProjectMemberMapper;
import com.buukle.agent.project.repository.PmRequirementActivityMapper;
import com.buukle.agent.project.repository.PmRequirementCommentMapper;
import com.buukle.agent.project.repository.PmRequirementMapper;
import com.buukle.agent.project.service.ProjectService;
import com.buukle.agent.project.service.ProjectContextEventService;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private static final String STATUS_ACTIVE = ProjectStatus.ACTIVE.value();
    private static final String STATUS_ARCHIVED = ProjectStatus.ARCHIVED.value();
    private static final String VISIBILITY_PRIVATE = "PRIVATE";
    private static final String ROLE_OWNER = ProjectMemberRole.OWNER.value();
    private static final String ROLE_MANAGER = ProjectMemberRole.MANAGER.value();
    private static final String ROLE_MEMBER = ProjectMemberRole.MEMBER.value();
    private static final String ROLE_VIEWER = ProjectMemberRole.VIEWER.value();
    private static final Set<String> REQUIREMENT_STATUSES = Set.of(
            RequirementStatus.DRAFT, RequirementStatus.REVIEWING,
            RequirementStatus.APPROVED, RequirementStatus.IN_PROGRESS,
            RequirementStatus.DONE, RequirementStatus.REJECTED);
    private static final String REQUIREMENT_STATUS_DRAFT = RequirementStatus.DRAFT;
    private static final String COMMENT_STATUS_ACTIVE = "ACTIVE";
    private static final String REQUIREMENT_KEY_FORMAT = "REQ-%04d";

    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper memberMapper;
    private final PmRequirementMapper requirementMapper;
    private final PmRequirementActivityMapper activityMapper;
    private final PmRequirementCommentMapper commentMapper;
    private final ProjectKeyGenerator projectKeyGenerator;
    private final UserSpi userSpi;
    private final ProjectContextEventService contextEventService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public PageResult<ProjectVO> page(String keyword, int page, int pageSize) {
        Long tenantId = currentTenant();
        Long userId = currentUser();
        LambdaQueryWrapper<PmProject> wrapper = new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getTenantId, tenantId)
                .eq(PmProject::getStatus, STATUS_ACTIVE)
                .inSql(PmProject::getId,
                        "select project_id from pm_project_member where delete_flag = false and user_id = "
                                + userId + " and tenant_id = " + tenantId);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(PmProject::getName, keyword)
                    .or().like(PmProject::getProjectKey, keyword)
                    .or().like(PmProject::getDescription, keyword));
        }
        wrapper.orderByDesc(PmProject::getUpdatedAt);
        Page<PmProject> result = projectMapper.selectPage(Page.of(page, pageSize), wrapper);
        Map<Long, UserIdentityVO> identities = identities(
                result.getRecords().stream().map(PmProject::getOwnerUserId).toList());
        return PageResult.of(result.getRecords().stream()
                        .map(project -> toProjectVO(project, identities)).toList(),
                result.getTotal(), page, pageSize);
    }

    @Override
    public ProjectVO detail(Long id) {
        PmProject project = requireVisible(id);
        return toProjectVO(project, identities(List.of(project.getOwnerUserId())));
    }

    @Override
    @Transactional
    public Long create(CreateProjectDTO dto) {
        Long tenantId = currentTenant();
        Long userId = currentUser();
        String projectKey = projectKeyGenerator.next(tenantId);
        Long existing = projectMapper.selectCount(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getTenantId, tenantId)
                .eq(PmProject::getProjectKey, projectKey));
        if (existing > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "project key already exists");
        }
        PmProject project = new PmProject();
        project.setProjectKey(projectKey);
        project.setName(dto.getName().trim());
        project.setDescription(dto.getDescription());
        project.setOwnerUserId(userId);
        project.setVisibility(VISIBILITY_PRIVATE);
        project.setStatus(STATUS_ACTIVE);
        project.setStartDate(dto.getStartDate());
        project.setEndDate(dto.getEndDate());
        project.setRequirementSeq(0L);
        project.setTenantId(tenantId);
        projectMapper.insert(project);

        PmProjectMember owner = new PmProjectMember();
        owner.setProjectId(project.getId());
        owner.setUserId(userId);
        owner.setMemberRole(ROLE_OWNER);
        owner.setJoinedAt(LocalDateTime.now());
        owner.setTenantId(tenantId);
        memberMapper.insert(owner);
        recordContext(project.getId(), null, ProjectContextEventType.PROJECT.value(),
                "Project \"" + project.getName() + "\" created by " + currentDisplayName());
        return project.getId();
    }

    @Override
    @Transactional
    public void update(Long id, UpdateProjectDTO dto) {
        PmProject project = requireManager(id);
        if (StringUtils.hasText(dto.getName())) {
            project.setName(dto.getName().trim());
        }
        if (dto.getDescription() != null) {
            project.setDescription(dto.getDescription());
        }
        if (dto.getStartDate() != null) {
            project.setStartDate(dto.getStartDate());
        }
        if (dto.getEndDate() != null) {
            project.setEndDate(dto.getEndDate());
        }
        projectMapper.updateById(project);
        recordContext(id, null, ProjectContextEventType.PROJECT.value(),
                "Project \"" + project.getName() + "\" updated by " + currentDisplayName());
    }

    @Override
    @Transactional
    public void archive(Long id) {
        PmProject project = requireOwner(id);
        project.setStatus(STATUS_ARCHIVED);
        projectMapper.updateById(project);
        recordContext(id, null, ProjectContextEventType.PROJECT.value(),
                "Project \"" + project.getName() + "\" archived by " + currentDisplayName());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        PmProject project = requireOwner(id);
        List<PmRequirement> requirements = requirementMapper.selectList(
                new LambdaQueryWrapper<PmRequirement>()
                        .eq(PmRequirement::getProjectId, id));
        if (!requirements.isEmpty()) {
            List<Long> requirementIds = requirements.stream()
                    .map(PmRequirement::getId)
                    .toList();
            activityMapper.delete(new LambdaQueryWrapper<PmRequirementActivity>()
                    .in(PmRequirementActivity::getRequirementId, requirementIds));
        }
        requirementMapper.delete(new LambdaQueryWrapper<PmRequirement>()
                .eq(PmRequirement::getProjectId, id));
        memberMapper.delete(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, id));
        recordContext(id, null, ProjectContextEventType.PROJECT.value(),
                "Project \"" + project.getName() + "\" deleted by " + currentDisplayName());
        projectMapper.deleteById(project.getId());
    }

    @Override
    public List<ProjectMemberVO> members(Long projectId) {
        requireVisible(projectId);
        List<PmProjectMember> members = memberMapper.selectList(
                new LambdaQueryWrapper<PmProjectMember>()
                        .eq(PmProjectMember::getProjectId, projectId)
                        .orderByAsc(PmProjectMember::getJoinedAt));
        Map<Long, UserIdentityVO> identities = identities(
                members.stream().map(PmProjectMember::getUserId).toList());
        return members.stream().map(member -> toMemberVO(member, identities)).toList();
    }

    @Override
    @Transactional
    public void addMember(Long projectId, AddProjectMemberDTO dto) {
        requireManager(projectId);
        Long existing = memberMapper.selectCount(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .eq(PmProjectMember::getUserId, dto.getUserId()));
        if (existing > 0) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "member already exists");
        }
        PmProjectMember member = new PmProjectMember();
        member.setProjectId(projectId);
        member.setUserId(dto.getUserId());
        member.setMemberRole(StringUtils.hasText(dto.getMemberRole()) ? dto.getMemberRole() : ROLE_MEMBER);
        member.setJoinedAt(LocalDateTime.now());
        member.setTenantId(currentTenant());
        memberMapper.insert(member);
        recordContext(projectId, null, ProjectContextEventType.MEMBER.value(),
                "Member added: " + displayName(dto.getUserId()) + " by " + currentDisplayName());
    }

    @Override
    @Transactional
    public void removeMember(Long projectId, Long userId) {
        PmProject project = requireManager(projectId);
        if (project.getOwnerUserId().equals(userId)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "project owner cannot be removed");
        }
        PmProjectMember member = memberMapper.selectOne(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .eq(PmProjectMember::getUserId, userId));
        if (member != null) {
            memberMapper.deleteById(member.getId());
        }
        recordContext(projectId, null, ProjectContextEventType.MEMBER.value(),
                "Member removed: " + displayName(userId) + " by " + currentDisplayName());
    }

    @Override
    public PageResult<RequirementVO> requirements(Long projectId, String status, int page, int pageSize) {
        PmProject project = requireVisible(projectId);
        LambdaQueryWrapper<PmRequirement> wrapper = new LambdaQueryWrapper<PmRequirement>()
                .eq(PmRequirement::getProjectId, projectId);
        if (StringUtils.hasText(status)) {
            wrapper.eq(PmRequirement::getStatus, status);
        }
        wrapper.orderByAsc(PmRequirement::getPriority).orderByDesc(PmRequirement::getUpdatedAt);
        Page<PmRequirement> result = requirementMapper.selectPage(Page.of(page, pageSize), wrapper);
        Map<Long, UserIdentityVO> identities = identities(
                result.getRecords().stream()
                        .flatMap(requirement -> Stream.of(
                                requirement.getOwnerUserId(), requirement.getAssigneeUserId()))
                        .toList());
        Map<Long, String> projectNames = Map.of(project.getId(), project.getName());
        return PageResult.of(result.getRecords().stream()
                        .map(requirement -> toRequirementVO(
                                requirement, identities, projectNames))
                        .toList(),
                result.getTotal(), page, pageSize);
    }

    @Override
    public PageResult<RequirementVO> requirementPage(Long projectId, String status,
                                                      String keyword, int page, int pageSize) {
        Long tenantId = currentTenant();
        Long userId = currentUser();
        LambdaQueryWrapper<PmRequirement> wrapper = new LambdaQueryWrapper<PmRequirement>()
                .eq(PmRequirement::getTenantId, tenantId);
        if (projectId != null) {
            requireMember(projectId);
            wrapper.eq(PmRequirement::getProjectId, projectId);
        } else {
            wrapper.inSql(PmRequirement::getProjectId,
                    "select project_id from pm_project_member where delete_flag = false and user_id = "
                            + userId + " and tenant_id = " + tenantId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(PmRequirement::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(PmRequirement::getTitle, keyword)
                    .or().like(PmRequirement::getRequirementKey, keyword)
                    .or().like(PmRequirement::getDescription, keyword));
        }
        wrapper.orderByAsc(PmRequirement::getPriority)
                .orderByDesc(PmRequirement::getUpdatedAt);
        Page<PmRequirement> result = requirementMapper.selectPage(
                Page.of(page, pageSize), wrapper);
        Map<Long, UserIdentityVO> identities = identities(
                result.getRecords().stream()
                        .flatMap(requirement -> Stream.of(
                                requirement.getOwnerUserId(),
                                requirement.getAssigneeUserId()))
                        .toList());
        Map<Long, String> projectNames = projectNames(result.getRecords().stream()
                .map(PmRequirement::getProjectId)
                .toList());
        return PageResult.of(result.getRecords().stream()
                        .map(requirement -> toRequirementVO(
                                requirement, identities, projectNames))
                        .toList(),
                result.getTotal(), page, pageSize);
    }

    @Override
    public RequirementVO requirement(Long id) {
        PmRequirement requirement = requireRequirement(id);
        return toRequirementVO(requirement,
                identities(Stream.of(requirement.getOwnerUserId(),
                        requirement.getAssigneeUserId()).toList()),
                projectNames(List.of(requirement.getProjectId())));
    }

    @Override
    @Transactional
    public Long createRequirement(Long projectId, CreateRequirementDTO dto) {
        PmProject project = requireMember(projectId);
        if (!STATUS_ACTIVE.equals(project.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "project is archived");
        }
        projectMapper.incrementRequirementSeq(projectId);
        Long seq = projectMapper.selectRequirementSeq(projectId);
        PmRequirement requirement = new PmRequirement();
        requirement.setProjectId(projectId);
        requirement.setRequirementKey(String.format(REQUIREMENT_KEY_FORMAT, seq));
        requirement.setTitle(dto.getTitle().trim());
        requirement.setDescription(dto.getDescription());
        requirement.setStatus(REQUIREMENT_STATUS_DRAFT);
        requirement.setPriority(dto.getPriority() == null ? 100 : dto.getPriority());
        requirement.setOwnerUserId(currentUser());
        requirement.setAssigneeUserId(dto.getAssigneeUserId());
        requirement.setStartDate(dto.getStartDate());
        requirement.setDueDate(dto.getDueDate());
        requirement.setTenantId(currentTenant());
        requirementMapper.insert(requirement);
        recordActivity(requirement.getId(), RequirementActivityType.CREATE,
                null, RequirementStatus.DRAFT);
        recordContext(projectId, requirement.getId(), ProjectContextEventType.REQUIREMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " \"" + requirement.getTitle()
                        + "\" created by " + currentDisplayName());
        publishRequirementChanged(projectId, requirement.getId());
        return requirement.getId();
    }

    @Override
    @Transactional
    public void updateRequirement(Long id, UpdateRequirementDTO dto) {
        PmRequirement requirement = requireRequirement(id);
        requireMember(requirement.getProjectId());
        if (StringUtils.hasText(dto.getTitle())) {
            requirement.setTitle(dto.getTitle().trim());
        }
        if (dto.getDescription() != null) {
            requirement.setDescription(dto.getDescription());
        }
        if (dto.getPriority() != null) {
            requirement.setPriority(dto.getPriority());
        }
        if (dto.getAssigneeUserId() != null) {
            requirement.setAssigneeUserId(dto.getAssigneeUserId());
        }
        if (dto.getStartDate() != null) {
            requirement.setStartDate(dto.getStartDate());
        }
        if (dto.getDueDate() != null) {
            requirement.setDueDate(dto.getDueDate());
        }
        requirementMapper.updateById(requirement);
        recordActivity(requirement.getId(), RequirementActivityType.UPDATE, null, null);
        recordContext(requirement.getProjectId(), requirement.getId(), ProjectContextEventType.REQUIREMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " updated by " + currentDisplayName());
        publishRequirementChanged(requirement.getProjectId(), requirement.getId());
    }

    @Override
    @Transactional
    public void changeRequirementStatus(Long id, String status) {
        if (!REQUIREMENT_STATUSES.contains(status)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "invalid requirement status");
        }
        PmRequirement requirement = requireRequirement(id);
        requireMember(requirement.getProjectId());
        String from = requirement.getStatus();
        requirement.setStatus(status);
        requirementMapper.updateById(requirement);
        recordActivity(requirement.getId(), RequirementActivityType.STATUS, from, status);
        recordContext(requirement.getProjectId(), requirement.getId(), ProjectContextEventType.REQUIREMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " status " + from + " -> " + status
                        + " by " + currentDisplayName());
        publishRequirementChanged(requirement.getProjectId(), requirement.getId());
    }

    @Override
    @Transactional
    public void deleteRequirement(Long id) {
        PmRequirement requirement = requireRequirement(id);
        requireManager(requirement.getProjectId());
        activityMapper.delete(new LambdaQueryWrapper<PmRequirementActivity>()
                .eq(PmRequirementActivity::getRequirementId, id));
        commentMapper.delete(new LambdaQueryWrapper<PmRequirementComment>()
                .eq(PmRequirementComment::getRequirementId, id));
        requirementMapper.deleteById(id);
        recordContext(requirement.getProjectId(), null, ProjectContextEventType.REQUIREMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " deleted by " + currentDisplayName());
        publishRequirementChanged(requirement.getProjectId(), id);
    }

    @Override
    public List<RequirementActivityVO> activities(Long requirementId) {
        PmRequirement requirement = requireRequirement(requirementId);
        requireVisible(requirement.getProjectId());
        return activityMapper.selectList(new LambdaQueryWrapper<PmRequirementActivity>()
                        .eq(PmRequirementActivity::getRequirementId, requirementId)
                        .orderByDesc(PmRequirementActivity::getCreatedAt))
                .stream().map(this::toActivityVO).toList();
    }

    @Override
    public List<RequirementCommentVO> requirementComments(Long requirementId) {
        PmRequirement requirement = requireRequirement(requirementId);
        requireMember(requirement.getProjectId());
        List<PmRequirementComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<PmRequirementComment>()
                        .eq(PmRequirementComment::getRequirementId, requirementId)
                        .orderByAsc(PmRequirementComment::getCreatedAt)
                        .orderByAsc(PmRequirementComment::getId));
        Map<Long, UserIdentityVO> identities = identities(
                comments.stream().map(PmRequirementComment::getUserId).toList());
        return comments.stream()
                .map(comment -> toCommentVO(comment, identities))
                .toList();
    }

    @Override
    @Transactional
    public RequirementCommentVO addRequirementComment(Long requirementId,
                                                      CreateRequirementCommentDTO dto) {
        PmRequirement requirement = requireRequirement(requirementId);
        requireMember(requirement.getProjectId());
        Long userId = currentUser();
        String content = dto.getContent() == null ? "" : dto.getContent().trim();
        List<RequirementAttachmentVO> attachments = dto.getAttachments() == null
                ? List.of() : dto.getAttachments();
        if (content.isEmpty() && attachments.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "comment content or attachments are required");
        }
        PmRequirementComment comment = new PmRequirementComment();
        comment.setRequirementId(requirementId);
        comment.setUserId(userId);
        comment.setContent(content);
        if (!attachments.isEmpty()) {
            comment.setAttachments(JsonUtils.toJson(attachments));
        }
        comment.setStatus(COMMENT_STATUS_ACTIVE);
        comment.setTenantId(currentTenant());
        commentMapper.insert(comment);
        recordContext(requirement.getProjectId(), requirementId, ProjectContextEventType.COMMENT.value(),
                "Requirement " + requirement.getRequirementKey() + " comment: "
                        + preview(content) + " by " + currentDisplayName());
        publishRequirementChanged(requirement.getProjectId(), requirementId);
        return toCommentVO(comment, identities(List.of(userId)));
    }

    private PmProject requireVisible(Long id) {
        return requireMember(id);
    }

    private PmProject requireMember(Long projectId) {
        return requireRole(projectId, ROLE_VIEWER);
    }

    private PmProject requireManager(Long projectId) {
        return requireRole(projectId, ROLE_MANAGER);
    }

    private PmProject requireOwner(Long projectId) {
        return requireRole(projectId, ROLE_OWNER);
    }

    private PmProject requireRole(Long projectId, String minimumRole) {
        PmProject project = projectMapper.selectById(projectId);
        if (project == null || !currentTenant().equals(project.getTenantId())) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "project not found");
        }
        Long userId = currentUser();
        if (project.getOwnerUserId().equals(userId)) {
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

    private PmRequirement requireRequirement(Long id) {
        PmRequirement requirement = requirementMapper.selectById(id);
        if (requirement == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "requirement not found");
        }
        return requirement;
    }

    private int roleRank(String role) {
        return ProjectMemberRole.rankOf(role);
    }

    private void recordActivity(Long requirementId, String type, String from, String to) {
        PmRequirementActivity activity = new PmRequirementActivity();
        activity.setRequirementId(requirementId);
        activity.setActorUserId(currentUser());
        activity.setActivityType(type);
        activity.setFromValue(from);
        activity.setToValue(to);
        activity.setTenantId(currentTenant());
        activityMapper.insert(activity);
    }

    private ProjectVO toProjectVO(PmProject project,
                                  Map<Long, UserIdentityVO> identities) {
        ProjectVO vo = new ProjectVO();
        vo.setId(project.getId());
        vo.setProjectKey(project.getProjectKey());
        vo.setName(project.getName());
        vo.setDescription(project.getDescription());
        vo.setOwnerUserId(project.getOwnerUserId());
        vo.setOwnerName(displayName(project.getOwnerUserId(), identities));
        vo.setStatus(project.getStatus());
        vo.setStartDate(project.getStartDate());
        vo.setEndDate(project.getEndDate());
        vo.setRequirementSeq(project.getRequirementSeq());
        vo.setCreatedAt(project.getCreatedAt());
        vo.setUpdatedAt(project.getUpdatedAt());
        return vo;
    }

    private ProjectMemberVO toMemberVO(PmProjectMember member,
                                       Map<Long, UserIdentityVO> identities) {
        ProjectMemberVO vo = new ProjectMemberVO();
        vo.setId(member.getId());
        vo.setProjectId(member.getProjectId());
        vo.setUserId(member.getUserId());
        vo.setDisplayName(displayName(member.getUserId(), identities));
        vo.setMemberRole(member.getMemberRole());
        vo.setJoinedAt(member.getJoinedAt());
        return vo;
    }

    private RequirementVO toRequirementVO(PmRequirement requirement,
                                           Map<Long, UserIdentityVO> identities,
                                           Map<Long, String> projectNames) {
        RequirementVO vo = new RequirementVO();
        vo.setId(requirement.getId());
        vo.setProjectId(requirement.getProjectId());
        vo.setProjectName(projectNames.get(requirement.getProjectId()));
        vo.setRequirementKey(requirement.getRequirementKey());
        vo.setTitle(requirement.getTitle());
        vo.setDescription(requirement.getDescription());
        vo.setStatus(requirement.getStatus());
        vo.setPriority(requirement.getPriority());
        vo.setOwnerUserId(requirement.getOwnerUserId());
        vo.setOwnerName(displayName(requirement.getOwnerUserId(), identities));
        vo.setAssigneeUserId(requirement.getAssigneeUserId());
        vo.setAssigneeName(displayName(requirement.getAssigneeUserId(), identities));
        vo.setStartDate(requirement.getStartDate());
        vo.setDueDate(requirement.getDueDate());
        vo.setCreatedAt(requirement.getCreatedAt());
        vo.setUpdatedAt(requirement.getUpdatedAt());
        return vo;
    }

    private RequirementCommentVO toCommentVO(PmRequirementComment comment,
                                             Map<Long, UserIdentityVO> identities) {
        RequirementCommentVO vo = new RequirementCommentVO();
        vo.setId(comment.getId());
        vo.setRequirementId(comment.getRequirementId());
        vo.setUserId(comment.getUserId());
        vo.setUserName(displayName(comment.getUserId(), identities));
        vo.setContent(comment.getContent());
        if (StringUtils.hasText(comment.getAttachments())) {
            List<RequirementAttachmentVO> attachments = JsonUtils.parse(
                    comment.getAttachments(),
                    new TypeReference<List<RequirementAttachmentVO>>() {
                    });
            vo.setAttachments(attachments == null ? List.of() : attachments);
        }
        vo.setCreatedAt(comment.getCreatedAt());
        return vo;
    }

    private RequirementActivityVO toActivityVO(PmRequirementActivity activity) {
        RequirementActivityVO vo = new RequirementActivityVO();
        vo.setId(activity.getId());
        vo.setRequirementId(activity.getRequirementId());
        vo.setActorUserId(activity.getActorUserId());
        vo.setActivityType(activity.getActivityType());
        vo.setFromValue(activity.getFromValue());
        vo.setToValue(activity.getToValue());
        vo.setCreatedAt(activity.getCreatedAt());
        return vo;
    }

    private Map<Long, String> projectNames(Collection<Long> projectIds) {
        Set<Long> ids = projectIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(PmProject::getId, PmProject::getName,
                        (left, right) -> left));
    }

    private Map<Long, UserIdentityVO> identities(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : userSpi.identityMap(ids);
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

    private void recordContext(Long projectId, Long requirementId, String type, String summary) {
        try {
            contextEventService.record(projectId, requirementId, type, summary,
                    null, null, currentUser());
        } catch (Exception e) {
            log.warn("Project context record failed: project={}, type={}, error={}",
                    projectId, type, e.getMessage());
        }
    }

    private void publishRequirementChanged(Long projectId, Long requirementId) {
        try {
            eventPublisher.publishEvent(new ProjectRequirementChangedEvent(
                    currentTenant(), projectId, requirementId));
        } catch (Exception e) {
            log.warn("Project change event publish failed: project={}, error={}",
                    projectId, e.getMessage());
        }
    }

    private String currentDisplayName() {
        return displayName(currentUser());
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

    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        String text = content.replaceAll("\\s+", " ").strip();
        return text.length() <= 300 ? text : text.substring(0, 300);
    }
}


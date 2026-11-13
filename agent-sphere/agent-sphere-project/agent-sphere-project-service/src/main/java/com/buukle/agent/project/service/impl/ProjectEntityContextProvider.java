package com.buukle.agent.project.service.impl;

import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.project.dtvo.ProjectVO;
import com.buukle.agent.project.dtvo.RequirementVO;
import com.buukle.agent.project.dtvo.enums.RequirementStatus;
import com.buukle.agent.project.service.ProjectService;
import com.buukle.agent.superagent.spi.EntityContext;
import com.buukle.agent.superagent.spi.EntityContextSpi;
import com.buukle.agent.superagent.spi.EntityTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectEntityContextProvider implements EntityContextSpi {

    private static final String ATTR_PROJECT_KEY = "project_key";
    private static final String ATTR_PROJECT_NAME = "project_name";
    private static final String ATTR_PROJECT_STATUS = "project_status";
    private static final String ATTR_OWNER = "owner_user_id";
    private static final String ATTR_MEMBER_COUNT = "member_count";
    private static final String ATTR_REQUIREMENT_TOTAL = "requirement_total";
    private static final String ATTR_REQUIREMENT_OVERDUE = "requirement_overdue";
    private static final String ATTR_REQUIREMENT_HIGH_PRIORITY = "requirement_high_priority";
    private static final int REQUIREMENT_SCAN_LIMIT = 100;
    private static final int HIGH_PRIORITY_MAX = 50;
    private static final String ATTR_REQUIREMENT_KEY = "requirement_key";
    private static final String ATTR_PRIORITY = "priority";
    private static final String ATTR_ASSIGNEE = "assignee_user_id";
    private static final String ATTR_DUE_DATE = "due_date";

    private final ProjectService projectService;

    @Override
    public boolean supports(String entityType) {
        return EntityTypes.PROJECT.equals(entityType)
                || EntityTypes.REQUIREMENT.equals(entityType);
    }

    @Override
    public EntityContext describe(String entityType, Long entityId, Long userId) {
        if (entityId == null) {
            return null;
        }
        try {
            if (EntityTypes.PROJECT.equals(entityType)) {
                return describeProject(projectService.detail(entityId));
            }
            if (EntityTypes.REQUIREMENT.equals(entityType)) {
                RequirementVO requirement = projectService.requirement(entityId);
                ProjectVO project = projectService.detail(requirement.getProjectId());
                return describeRequirement(requirement, project);
            }
            return null;
        } catch (BizException e) {
            return null;
        }
    }

    private EntityContext describeProject(ProjectVO project) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, ATTR_PROJECT_KEY, project.getProjectKey());
        put(attributes, ATTR_OWNER, project.getOwnerUserId());
        int memberCount = projectService.members(project.getId()).size();
        put(attributes, ATTR_MEMBER_COUNT, memberCount);
        PageResult<RequirementVO> requirementPage = projectService.requirements(
                project.getId(), null, 1, REQUIREMENT_SCAN_LIMIT);
        List<RequirementVO> rows = requirementPage.getRecords();
        LocalDate today = LocalDate.now();
        long overdue = rows.stream()
                .filter(row -> row.getDueDate() != null
                        && row.getDueDate().isBefore(today)
                        && !RequirementStatus.DONE.equals(row.getStatus()))
                .count();
        long highPriority = rows.stream()
                .filter(row -> row.getPriority() != null
                        && row.getPriority() <= HIGH_PRIORITY_MAX
                        && !RequirementStatus.DONE.equals(row.getStatus()))
                .count();
        put(attributes, ATTR_REQUIREMENT_TOTAL, requirementPage.getTotal());
        put(attributes, ATTR_REQUIREMENT_OVERDUE, overdue);
        put(attributes, ATTR_REQUIREMENT_HIGH_PRIORITY, highPriority);
        return new EntityContext(EntityTypes.PROJECT, project.getId(),
                project.getName(), project.getStatus(), project.getDescription(), attributes);
    }

    private EntityContext describeRequirement(RequirementVO requirement, ProjectVO project) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, ATTR_PROJECT_NAME, project.getName());
        put(attributes, ATTR_PROJECT_STATUS, project.getStatus());
        put(attributes, ATTR_REQUIREMENT_KEY, requirement.getRequirementKey());
        put(attributes, ATTR_PRIORITY, requirement.getPriority());
        put(attributes, ATTR_OWNER, requirement.getOwnerUserId());
        put(attributes, ATTR_ASSIGNEE, requirement.getAssigneeUserId());
        put(attributes, ATTR_DUE_DATE, requirement.getDueDate());
        return new EntityContext(EntityTypes.REQUIREMENT, requirement.getId(),
                requirement.getTitle(), requirement.getStatus(),
                requirement.getDescription(), attributes);
    }

    private static void put(Map<String, String> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, String.valueOf(value));
        }
    }
}

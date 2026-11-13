package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.project.domain.PmProjectInsight;
import com.buukle.agent.project.domain.PmRequirement;
import com.buukle.agent.project.dtvo.ProjectInsightVO;
import com.buukle.agent.project.repository.PmProjectInsightMapper;
import com.buukle.agent.project.repository.PmRequirementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Project-level analysis results and their shared-context projection. */
@Service
@RequiredArgsConstructor
public class ProjectInsightService {

    private static final int MAX_CONTENT = 8000;
    private static final int CONTEXT_PREVIEW = 600;

    private final PmProjectInsightMapper insightMapper;
    private final PmRequirementMapper requirementMapper;
    private final ProjectContextEventService contextEventService;

    public Long create(Long projectId, Long requirementId, String insightType, String severity,
                       String title, String content, String source, Long sourceRunId,
                       Long sourceSessionId, Long canonicalSessionId, Long actorUserId) {
        if (projectId == null || !StringUtils.hasText(content)) {
            return null;
        }
        PmProjectInsight insight = new PmProjectInsight();
        insight.setProjectId(projectId);
        insight.setRequirementId(requirementId);
        insight.setInsightType(StringUtils.hasText(insightType) ? insightType : "SUMMARY");
        insight.setSeverity(StringUtils.hasText(severity) ? severity : "INFO");
        insight.setTitle(title);
        insight.setContent(content.length() <= MAX_CONTENT ? content : content.substring(0, MAX_CONTENT));
        insight.setSource(StringUtils.hasText(source) ? source : "MANUAL");
        insight.setSourceRunId(sourceRunId);
        insight.setStatus(com.buukle.agent.common.constant.ProjectAssistantConstants.STATUS_ACTIVE);
        insightMapper.insert(insight);

        String prefix = requirementId == null ? "Project insight" : "Requirement insight";
        String summary = prefix + "：" + (StringUtils.hasText(title) ? title + " - " : "")
                + preview(content);
        contextEventService.record(projectId, requirementId, "INSIGHT", summary,
                sourceSessionId, canonicalSessionId, actorUserId);
        return insight.getId();
    }

    public List<ProjectInsightVO> list(Long projectId, Long requirementId, int limit) {
        if (projectId == null) {
            return List.of();
        }
        int capped = limit <= 0 ? 20 : Math.min(limit, 100);
        List<PmProjectInsight> rows = insightMapper.selectList(
                new LambdaQueryWrapper<PmProjectInsight>()
                        .eq(PmProjectInsight::getProjectId, projectId)
                        .eq(requirementId != null, PmProjectInsight::getRequirementId, requirementId)
                        .orderByDesc(PmProjectInsight::getCreatedAt)
                        .orderByDesc(PmProjectInsight::getId)
                        .last("LIMIT " + capped));
        Map<Long, String> keys = requirementKeys(rows.stream()
                .map(PmProjectInsight::getRequirementId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return rows.stream().map(row -> toVO(row, keys)).toList();
    }

    private Map<Long, String> requirementKeys(java.util.Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return requirementMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(PmRequirement::getId, PmRequirement::getRequirementKey,
                        (left, right) -> left));
    }

    private ProjectInsightVO toVO(PmProjectInsight insight, Map<Long, String> keys) {
        ProjectInsightVO vo = new ProjectInsightVO();
        vo.setId(insight.getId());
        vo.setProjectId(insight.getProjectId());
        vo.setRequirementId(insight.getRequirementId());
        vo.setRequirementKey(insight.getRequirementId() == null
                ? null : keys.get(insight.getRequirementId()));
        vo.setInsightType(insight.getInsightType());
        vo.setSeverity(insight.getSeverity());
        vo.setTitle(insight.getTitle());
        vo.setContent(insight.getContent());
        vo.setSource(insight.getSource());
        vo.setSourceRunId(insight.getSourceRunId());
        vo.setCreatedBy(insight.getCreatedBy());
        vo.setCreatedAt(insight.getCreatedAt());
        return vo;
    }

    private static String preview(String content) {
        String text = content.replaceAll("\\s+", " ").strip();
        return text.length() <= CONTEXT_PREVIEW ? text : text.substring(0, CONTEXT_PREVIEW);
    }
}

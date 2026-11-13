package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.project.domain.PmProjectContextEvent;
import com.buukle.agent.project.dtvo.ProjectContextEventVO;
import com.buukle.agent.project.dtvo.enums.ProjectAnalysisStatus;
import com.buukle.agent.project.repository.PmProjectContextEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only project context log shared by the canonical project-assistant session and every
 * user fork.
 */
@Service
@RequiredArgsConstructor
public class ProjectContextEventService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int DEFAULT_LIMIT = 60;
    private static final int MAX_SUMMARY = 600;

    private final PmProjectContextEventMapper eventMapper;
    private final ProjectAssistantConfigService configService;

    public void record(Long projectId, Long requirementId, String eventType, String summary,
                       Long sourceSessionId, Long canonicalSessionId, Long actorUserId) {
        record(projectId, requirementId, eventType, summary, sourceSessionId, canonicalSessionId,
                actorUserId, true);
    }

    /** Record an event that must never enter the baseline analysis stream. */
    public void recordSkipped(Long projectId, Long requirementId, String eventType, String summary,
                              Long sourceSessionId, Long canonicalSessionId, Long actorUserId) {
        record(projectId, requirementId, eventType, summary, sourceSessionId, canonicalSessionId,
                actorUserId, false);
    }

    private void record(Long projectId, Long requirementId, String eventType, String summary,
                        Long sourceSessionId, Long canonicalSessionId, Long actorUserId,
                        boolean analyzable) {
        if (projectId == null || !StringUtils.hasText(summary)) {
            return;
        }
        PmProjectContextEvent event = new PmProjectContextEvent();
        event.setProjectId(projectId);
        event.setRequirementId(requirementId);
        event.setEventType(eventType);
        event.setSummary(truncate(summary));
        event.setSourceSessionId(sourceSessionId);
        event.setCanonicalSessionId(canonicalSessionId);
        event.setActorUserId(actorUserId);
        event.setStatus(com.buukle.agent.common.constant.ProjectAssistantConstants.STATUS_ACTIVE);
        event.setAnalysisStatus(analyzable && configService.isAutoAnalyze(projectId)
                ? ProjectAnalysisStatus.PENDING.value()
                : ProjectAnalysisStatus.SKIPPED.value());
        event.setAnalysisAttempts(0);
        eventMapper.insert(event);
    }

    public List<ProjectContextEventVO> recent(Long projectId, int limit) {
        if (projectId == null) {
            return List.of();
        }
        int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 200);
        List<PmProjectContextEvent> rows = eventMapper.selectList(
                new LambdaQueryWrapper<PmProjectContextEvent>()
                        .eq(PmProjectContextEvent::getProjectId, projectId)
                        .orderByDesc(PmProjectContextEvent::getCreatedAt)
                        .orderByDesc(PmProjectContextEvent::getId)
                        .last("LIMIT " + capped));
        return rows.stream().map(this::toVO).toList();
    }

    /** Newest entries that fit the character budget, returned oldest-first for prompt injection. */
    public String buildContext(Long projectId, int maxChars) {
        if (projectId == null) {
            return null;
        }
        int budget = maxChars <= 0 ? 6000 : maxChars;
        List<PmProjectContextEvent> rows = eventMapper.selectList(
                new LambdaQueryWrapper<PmProjectContextEvent>()
                        .eq(PmProjectContextEvent::getProjectId, projectId)
                        .orderByDesc(PmProjectContextEvent::getCreatedAt)
                        .orderByDesc(PmProjectContextEvent::getId)
                        .last("LIMIT 100"));
        if (rows.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (PmProjectContextEvent row : rows) {
            String line = "- [" + (row.getCreatedAt() == null ? "" : row.getCreatedAt().format(TIME))
                    + "] " + row.getSummary();
            if (used + line.length() + 1 > budget) {
                break;
            }
            lines.add(0, line);
            used += line.length() + 1;
        }
        if (lines.isEmpty()) {
            return null;
        }
        return "[Project shared context]\n" + String.join("\n", lines);
    }

    private ProjectContextEventVO toVO(PmProjectContextEvent event) {
        ProjectContextEventVO vo = new ProjectContextEventVO();
        vo.setId(event.getId());
        vo.setProjectId(event.getProjectId());
        vo.setRequirementId(event.getRequirementId());
        vo.setEventType(event.getEventType());
        vo.setSummary(event.getSummary());
        vo.setSourceSessionId(event.getSourceSessionId());
        vo.setCanonicalSessionId(event.getCanonicalSessionId());
        vo.setActorUserId(event.getActorUserId());
        vo.setCreatedAt(event.getCreatedAt());
        return vo;
    }

    private static String truncate(String value) {
        String text = value.strip();
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY);
    }
}

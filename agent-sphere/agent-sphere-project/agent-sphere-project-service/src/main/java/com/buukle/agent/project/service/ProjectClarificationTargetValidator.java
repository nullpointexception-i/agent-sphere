package com.buukle.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.spi.ClarificationTargetValidator;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.project.domain.PmProject;
import com.buukle.agent.project.domain.PmProjectMember;
import com.buukle.agent.project.repository.PmProjectMapper;
import com.buukle.agent.project.repository.PmProjectMemberMapper;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;

/** Only project members may be the target of a project-assistant question. */
@Service
@RequiredArgsConstructor
public class ProjectClarificationTargetValidator implements ClarificationTargetValidator {

    private final SessionSpi sessionSpi;
    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper memberMapper;

    @Override
    public boolean canTarget(Long sessionId, Long targetUserId) {
        if (sessionId == null || targetUserId == null) {
            return false;
        }
        SessionVO session;
        try {
            session = sessionSpi.getSession(sessionId);
        } catch (Exception e) {
            return false;
        }
        if (session == null || !SessionType.PROJECT_ROUTER.equals(session.getSessionType())) {
            // Not a project session: keep the default permissive behavior.
            return true;
        }
        Long projectId = parseLong(session.getContextData(), "projectId");
        if (projectId == null) {
            return false;
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            return false;
        }
        if (Objects.equals(project.getOwnerUserId(), targetUserId)) {
            return true;
        }
        return memberMapper.selectCount(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .eq(PmProjectMember::getUserId, targetUserId)) > 0;
    }

    private static Long parseLong(String contextData, String key) {
        if (!StringUtils.hasText(contextData)) {
            return null;
        }
        try {
            Map<String, Object> map = JsonUtils.parse(contextData,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object value = map == null ? null : map.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Long.parseLong(text);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}

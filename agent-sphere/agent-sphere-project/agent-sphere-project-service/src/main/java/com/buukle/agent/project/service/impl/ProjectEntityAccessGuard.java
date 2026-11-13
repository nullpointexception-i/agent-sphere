package com.buukle.agent.project.service.impl;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.project.service.ProjectService;
import com.buukle.agent.superagent.spi.EntityAccessGuard;
import com.buukle.agent.superagent.spi.EntityTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectEntityAccessGuard implements EntityAccessGuard {

    private final ProjectService projectService;

    @Override
    public boolean supports(String entityType) {
        return EntityTypes.PROJECT.equalsIgnoreCase(entityType)
                || EntityTypes.REQUIREMENT.equalsIgnoreCase(entityType);
    }

    @Override
    public void assertCanView(String entityType, Long entityId, Long userId) {
        if (EntityTypes.PROJECT.equalsIgnoreCase(entityType)) {
            projectService.detail(entityId);
            return;
        }
        if (EntityTypes.REQUIREMENT.equalsIgnoreCase(entityType)) {
            projectService.requirement(entityId);
            return;
        }
        throw new BizException(CommonErrorCode.PARAM_INVALID, "unsupported entity type");
    }
}

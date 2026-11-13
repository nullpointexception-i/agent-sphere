package com.buukle.agent.application.service.impl;

import com.buukle.agent.application.service.ApplicationService;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.superagent.spi.EntityAccessGuard;
import com.buukle.agent.superagent.spi.EntityTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApplicationEntityAccessGuard implements EntityAccessGuard {

    private final ApplicationService applicationService;

    @Override
    public boolean supports(String entityType) {
        return EntityTypes.APPLICATION.equalsIgnoreCase(entityType)
                || EntityTypes.APPLICATION_SCENE.equalsIgnoreCase(entityType);
    }

    @Override
    public void assertCanView(String entityType, Long entityId, Long userId) {
        if (EntityTypes.APPLICATION.equalsIgnoreCase(entityType)) {
            applicationService.detail(entityId);
            return;
        }
        if (EntityTypes.APPLICATION_SCENE.equalsIgnoreCase(entityType)) {
            applicationService.assertSceneVisible(entityId);
            return;
        }
        throw new BizException(CommonErrorCode.PARAM_INVALID, "unsupported entity type");
    }
}

package com.buukle.agent.runtime.orchestration.project;

import com.buukle.agent.common.constant.ProjectAssistantConstants;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.notification.NotificationKeys;
import com.buukle.agent.common.notification.NotificationTypes;
import com.buukle.agent.common.notification.UserNotificationCommand;
import com.buukle.agent.common.notification.UserNotificationPort;
import com.buukle.agent.instance.dtvo.vo.ClarificationVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.ClarificationSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.project.spi.ProjectAssistantSessionInfo;
import com.buukle.agent.project.spi.ProjectAssistantSessionPort;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.EntityTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Closes targeted project questions that were not answered before their expiry. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectAssistantExpiryTask {

    private static final int MAX_PER_SCAN = 100;

    private final ClarificationSpi clarificationSpi;
    private final SessionSpi sessionSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final UserNotificationPort notificationPort;
    private final ProjectAssistantSessionPort projectSessionPort;

    @Scheduled(fixedDelayString = "${buukle.agent.project-assistant.expiry-scan-ms:600000}")
    public void scan() {
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        try {
            List<ClarificationVO> expired = clarificationSpi.listExpired(
                    LocalDateTime.now(), MAX_PER_SCAN);
            for (ClarificationVO clarification : expired) {
                expire(clarification);
            }
        } catch (Exception e) {
            log.warn("Project assistant expiry scan failed: {}", e.getMessage());
        } finally {
            TenantUtil.stop();
            TenantScopeHolder.clear();
            TenantContext.clear();
            AuthContext.clear();
        }
    }

    private void expire(ClarificationVO clarification) {
        try {
            SessionVO session = clarification.getSessionId() == null
                    ? null : safeSession(clarification.getSessionId());
            if (session != null && clarification.getRunId() != null) {
                // Parked run: stopRun marks it CANCELLED and dismisses the pending question.
                chatRuntimeService.stopRun(session.getId(), clarification.getRunId());
            }
            if (clarification.getId() != null) {
                clarificationSpi.markExpired(clarification.getId());
            }
            if (session == null) {
                return;
            }
            ProjectAssistantSessionInfo link = projectSessionPort.findInfoBySessionId(session.getId());
            if (link == null || link.requesterUserId() == null) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("projectId", link.projectId());
            payload.put("clarificationId", clarification.getClarificationId());
            payload.put("question", clarification.getTitle());
            notificationPort.notifyUser(new UserNotificationCommand(
                    session.getTenantId(), link.requesterUserId(),
                    NotificationTypes.PROJECT_ASSISTANT_EXPIRED,
                    NotificationKeys.PROJECT_ASSISTANT_EXPIRED_TITLE,
                    NotificationKeys.PROJECT_ASSISTANT_EXPIRED_BODY,
                    payload, EntityTypes.PROJECT, link.projectId()));
        } catch (Exception e) {
            log.warn("Project question expiry failed: clarification={}, error={}",
                    clarification.getClarificationId(), e.getMessage());
        }
    }

    private SessionVO safeSession(Long sessionId) {
        try {
            return sessionSpi.getSession(sessionId);
        } catch (Exception e) {
            return null;
        }
    }
}

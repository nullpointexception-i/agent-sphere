package com.buukle.agent.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.application.domain.BizApplicationRun;
import com.buukle.agent.application.dtvo.enums.ApplicationRunStatus;
import com.buukle.agent.application.repository.BizApplicationRunMapper;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.common.notification.NotificationKeys;
import com.buukle.agent.common.notification.NotificationPayloadKeys;
import com.buukle.agent.common.notification.NotificationSourceTypes;
import com.buukle.agent.common.notification.NotificationTypes;
import com.buukle.agent.common.notification.UserNotificationCommand;
import com.buukle.agent.common.notification.UserNotificationPort;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.spi.WorkItemLifecycleSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ApplicationRunLifecycleListener implements WorkItemLifecycleSpi {

    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of(
            ApplicationRunStatus.SUCCEEDED,
            ApplicationRunStatus.FAILED,
            ApplicationRunStatus.CANCELLED);

    private final BizApplicationRunMapper runMapper;
    private final ObjectProvider<UserNotificationPort> notificationPortProvider;
    private final AgentMetrics metrics;

    @Override
    public void onStarted(Long workItemId) {
        BizApplicationRun run = findByWorkItem(workItemId);
        if (run == null || !ApplicationRunStatus.QUEUED.equals(run.getStatus())) {
            return;
        }
        run.setStatus(ApplicationRunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    @Override
    public void onTerminal(Long workItemId, String workItemStatus,
                           String summary, String errorMessage) {
        if (workItemId == null) {
            return;
        }
        BizApplicationRun run = findByWorkItem(workItemId);
        if (run == null) {
            return;
        }
        String runStatus = mapStatus(workItemStatus);
        if (runStatus == null) {
            return;
        }
        String previousStatus = run.getStatus();
        run.setStatus(runStatus);
        if (StringUtils.hasText(errorMessage)) {
            run.setErrorMessage(errorMessage);
        } else if (StringUtils.hasText(summary)) {
            run.setErrorMessage(summary);
        }
        if (TERMINAL_RUN_STATUSES.contains(runStatus)) {
            run.setFinishedAt(LocalDateTime.now());
        }
        runMapper.updateById(run);
        if (!runStatus.equals(previousStatus)) {
            metrics.increment(AgentMetrics.APPLICATION_RUN_TRANSITION,
                    AgentMetrics.TAG_STATUS, runStatus);
            notifyRun(run, runStatus);
        }
    }

    private void notifyRun(BizApplicationRun run, String runStatus) {
        if (run.getRequesterUserId() == null
                || (!TERMINAL_RUN_STATUSES.contains(runStatus)
                && !ApplicationRunStatus.WAITING_APPROVAL.equals(runStatus))) {
            return;
        }
        UserNotificationPort port = notificationPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put(NotificationPayloadKeys.APPLICATION_ID, run.getApplicationId());
        payload.put(NotificationPayloadKeys.SCENE_ID, run.getSceneId());
        payload.put(NotificationPayloadKeys.WORK_ITEM_ID, run.getWorkItemId());
        payload.put(NotificationPayloadKeys.STATUS, runStatus);
        port.notifyUser(new UserNotificationCommand(
                run.getTenantId(), run.getRequesterUserId(),
                NotificationTypes.APPLICATION_RUN,
                NotificationKeys.APPLICATION_RUN_TITLE,
                NotificationKeys.APPLICATION_RUN_BODY,
                payload, NotificationSourceTypes.APPLICATION_RUN, run.getId()));
    }

    private BizApplicationRun findByWorkItem(Long workItemId) {
        if (workItemId == null) {
            return null;
        }
        return runMapper.selectOne(new LambdaQueryWrapper<BizApplicationRun>()
                .eq(BizApplicationRun::getWorkItemId, workItemId)
                .last("limit 1"));
    }

    private String mapStatus(String workItemStatus) {
        if (WorkItemStatus.COMPLETED.equals(workItemStatus)
                || WorkItemStatus.DONE.equals(workItemStatus)) {
            return ApplicationRunStatus.SUCCEEDED;
        }
        if (WorkItemStatus.FAILED.equals(workItemStatus)) {
            return ApplicationRunStatus.FAILED;
        }
        if (WorkItemStatus.CANCELLED.equals(workItemStatus)) {
            return ApplicationRunStatus.CANCELLED;
        }
        if (WorkItemStatus.BLOCKED.equals(workItemStatus)) {
            return ApplicationRunStatus.WAITING_APPROVAL;
        }
        return null;
    }
}

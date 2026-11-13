package com.buukle.agent.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.buukle.agent.application.domain.BizApplicationScene;
import com.buukle.agent.application.repository.BizApplicationSceneMapper;
import com.buukle.agent.application.service.ApplicationRunService;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Dispatches PERIODIC scenes. Multi-replica safety comes from the run
 * idempotency key ({@code app:...:cron:...}), not from a process-local lock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "buukle.agent.application", name = "periodic-enabled",
        havingValue = "true")
public class ApplicationPeriodicScheduler {

    private static final String SCENE_TYPE_PERIODIC = "PERIODIC";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String CFG_ENABLED = "enabled";
    private static final String CFG_CRON = "cron";
    private static final String CFG_RUN_AS = "runAsUserId";

    private final BizApplicationSceneMapper sceneMapper;
    private final ApplicationRunService runService;

    private LocalDateTime lastCheck = LocalDateTime.now().withSecond(0).withNano(0);

    @Scheduled(fixedDelayString = "${buukle.agent.application.periodic-interval-ms:60000}")
    public void tick() {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime from = lastCheck;
        lastCheck = now;
        if (!now.isAfter(from)) {
            return;
        }
        List<BizApplicationScene> scenes = sceneMapper.selectList(
                new LambdaQueryWrapper<BizApplicationScene>()
                        .eq(BizApplicationScene::getSceneType, SCENE_TYPE_PERIODIC)
                        .eq(BizApplicationScene::getStatus, STATUS_ACTIVE));
        for (BizApplicationScene scene : scenes) {
            try {
                dispatchScene(scene, from, now);
            } catch (Exception e) {
                log.warn("Periodic scene dispatch failed: sceneId={}, error={}",
                        scene.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
                TenantScopeHolder.clear();
            }
        }
    }

    private void dispatchScene(BizApplicationScene scene, LocalDateTime from, LocalDateTime now) {
        JsonNode config = parseConfig(scene.getConfig());
        if (config == null || !config.path(CFG_ENABLED).asBoolean(false)) {
            return;
        }
        String cron = config.path(CFG_CRON).asText("");
        Long runAsUserId = config.hasNonNull(CFG_RUN_AS)
                ? config.get(CFG_RUN_AS).asLong() : null;
        if (!StringUtils.hasText(cron) || runAsUserId == null) {
            return;
        }
        CronExpression expression = CronExpression.parse(cron);
        LocalDateTime cursor = from;
        while (true) {
            LocalDateTime next = expression.next(cursor);
            if (next == null || next.isAfter(now)) {
                return;
            }
            TenantContext.set(scene.getTenantId());
            runService.triggerCron(scene.getId(), runAsUserId, next);
            cursor = next;
        }
    }

    private JsonNode parseConfig(String config) {
        if (!StringUtils.hasText(config)) {
            return null;
        }
        try {
            return JsonUtils.getMapper().readTree(config);
        } catch (Exception e) {
            return null;
        }
    }
}

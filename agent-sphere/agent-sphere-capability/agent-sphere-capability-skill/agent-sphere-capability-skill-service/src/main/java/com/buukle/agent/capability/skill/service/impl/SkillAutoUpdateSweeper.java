package com.buukle.agent.capability.skill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 已安装 skill 副本的自动更新扫描：分页读取「最近创建、开启了 auto_update」的已安装副本，
 * 逐条交由 {@link CapabilitySkillService#syncFromOrigin} 对比源版本并同步。
 *
 * 范围限定避免全表扫描爆炸：
 * - 只扫描 `created_at >= now - windowDays` 的副本（默认 7 天）；
 * - 按批次分页拉取（默认每批 100 条），不足一批即停止。
 *
 * 后台线程无 AuthContext/TenantUtil，DataPermissionInterceptor 因 tenant 为空直接放行，
 * 可跨用户读取公开源技能；同步逻辑内的条件更新保证多副本部署下同一副本只被更新一次。
 */
@Slf4j
@Component
public class SkillAutoUpdateSweeper {

    private final CapabilitySkillService capabilitySkillService;
    private final int windowDays;
    private final int batchSize;

    public SkillAutoUpdateSweeper(
            CapabilitySkillService capabilitySkillService,
            @Value("${buukle.agent.skill.auto-update-window-days:7}") int windowDays,
            @Value("${buukle.agent.skill.auto-update-batch-size:100}") int batchSize) {
        this.capabilitySkillService = capabilitySkillService;
        this.windowDays = Math.max(windowDays, 1);
        this.batchSize = Math.max(batchSize, 1);
    }

    @Scheduled(fixedDelayString = "${buukle.agent.skill.auto-update-interval:PT5M}")
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(windowDays);
        long pageNo = 1;
        int handled = 0;
        int updated = 0;
        int skipped = 0;
        while (true) {
            Page<CapabilitySkill> page = capabilitySkillService.page(
                    new Page<>(pageNo, batchSize),
                    new LambdaQueryWrapper<CapabilitySkill>()
                            .eq(CapabilitySkill::getAutoUpdate, true)
                            .isNotNull(CapabilitySkill::getOriginSkillId)
                            .ge(CapabilitySkill::getCreatedAt, cutoff)
                            .orderByDesc(CapabilitySkill::getCreatedAt));
            List<CapabilitySkill> rows = page.getRecords();
            if (rows.isEmpty()) {
                break;
            }
            for (CapabilitySkill copy : rows) {
                try {
                    if (capabilitySkillService.syncFromOrigin(copy)) {
                        updated++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.warn("skill auto-update sync failed: copyId={}, originId={}, msg={}",
                            copy.getId(), copy.getOriginSkillId(), e.getMessage());
                }
            }
            handled += rows.size();
            if (rows.size() < batchSize) {
                break;
            }
            pageNo++;
        }
        if (handled > 0) {
            log.info("skill auto-update sweep done: batchSize={}, windowDays={}, handled={}, updated={}, skipped={}",
                    batchSize, windowDays, handled, updated, skipped);
        }
    }
}
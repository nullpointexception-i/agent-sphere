package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncOutcome;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncTriggerEnum;
import com.buukle.agent.capability.skill.dtvo.vo.SkillSyncRunVO;
import com.buukle.agent.capability.skill.repository.SkillSyncRunMapper;
import com.buukle.agent.capability.skill.repository.SkillSyncRunRowVO;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.capability.skill.service.impl.SkillSyncRunner;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillSyncRunner}。
 *
 * <p>The most important one is {@code sweep_queryHasNoTimeWindow}: the root cause fixed here was the 7-day
 * window filtered by "install time", which meant copies installed long ago were never scanned again. The old test actually asserted
 * that the window existed, so it must now be locked down the other way.
 */
@ExtendWith(MockitoExtension.class)
class SkillSyncRunnerTest {

    private static final Long RECORD_ID = 7001L;
    private static final int BATCH_SIZE = 2;

    @Mock
    private CapabilitySkillService capabilitySkillService;
    @Mock
    private SkillSyncRunMapper skillSyncRunMapper;
    @Mock
    private SystemConfigSpi systemConfigSpi;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private Executor syncExecutor;

    private SkillSyncRunner runner;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CapabilitySkill.class);
        runner = new SkillSyncRunner(capabilitySkillService, skillSyncRunMapper, systemConfigSpi,
                redissonClient, syncExecutor);
        ReflectionTestUtils.setField(runner, "batchSize", BATCH_SIZE);

        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock()).thenReturn(true);
        lenient().when(skillSyncRunMapper.insertRunning(anyString(), anyString(), anyString()))
                .thenReturn(RECORD_ID);
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SKILL_AUTO_UPDATE_ENABLED), anyString()))
                .thenReturn("true");
        lenient().when(systemConfigSpi.get(eq(SystemConfigKeys.SKILL_SYNC_LOG_RETENTION_DAYS), anyString()))
                .thenReturn("30");
        lenient().when(skillSyncRunMapper.deleteOlderThan(any(), any())).thenReturn(0);
    }

    private CapabilitySkill copy(Long id, Long originId) {
        CapabilitySkill s = new CapabilitySkill();
        s.setId(id);
        s.setOriginSkillId(originId);
        // Installed long ago: exactly the scenario this fix covers
        s.setCreatedAt(LocalDateTime.now().minusDays(400));
        return s;
    }

    private Page<CapabilitySkill> pageOf(List<CapabilitySkill> rows) {
        Page<CapabilitySkill> page = new Page<>(1, BATCH_SIZE);
        page.setRecords(rows);
        page.setTotal(rows.size());
        return page;
    }

    @Test
    @DisplayName("扫描条件不得含时间窗口：装超过 7 天的副本也必须能被扫到（本次修复的回归防护）")
    void sweep_queryHasNoTimeWindow() {
        given(capabilitySkillService.page(any(), any())).willReturn(new Page<>());

        runner.runSync(SkillSyncTriggerEnum.TRIGGER_SCHEDULED);

        ArgumentCaptor<LambdaQueryWrapper<CapabilitySkill>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(capabilitySkillService).page(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.toLowerCase().contains("auto_update = ?"), "应限定 auto_update, 实际: " + sql);
        assertTrue(sql.toUpperCase().contains("ORIGIN_SKILL_ID IS NOT NULL"), "应限定已安装副本, 实际: " + sql);
        assertFalse(sql.toLowerCase().contains("created_at"),
                "扫描条件不能再按创建时间过滤 —— 那会让装久了的副本永远同步不到, 实际: " + sql);
        assertTrue(((java.util.Map<?, ?>) captor.getValue().getParamNameValuePairs()).values()
                        .stream().noneMatch(v -> v instanceof LocalDateTime),
                "不应再有时间窗口参数");
    }

    @Test
    @DisplayName("分页处理到不足一批为止，并按同步结果分别计数")
    void sweep_pagesAndCountsOutcomes() {
        given(capabilitySkillService.page(any(), any()))
                .willReturn(pageOf(List.of(copy(1L, 10L), copy(2L, 10L))))
                .willReturn(pageOf(List.of(copy(3L, 11L))));
        given(capabilitySkillService.syncWithOutcome(any()))
                .willReturn(SkillSyncOutcome.UPDATED)
                .willReturn(SkillSyncOutcome.VERSION_NOT_AHEAD)
                .willReturn(SkillSyncOutcome.SOURCE_GONE);

        runner.runSync(SkillSyncTriggerEnum.TRIGGER_SCHEDULED);

        verify(capabilitySkillService, times(2)).page(any(), any());
        verify(capabilitySkillService, times(3)).syncWithOutcome(any());
        verify(skillSyncRunMapper).markSuccess(eq(RECORD_ID), eq("SUCCESS"),
                eq(3), eq(3), eq(1), eq(2), eq(BATCH_SIZE),
                org.mockito.ArgumentMatchers.contains("SOURCE_GONE"),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("单个副本同步异常不影响其余，且进跳过明细")
    void sweep_isolatesPerCopyFailure() {
        // The second page must be empty: once stubs run out, Mockito keeps returning the last value,
        // and a full batch of 2 would be treated as "more to come", looping forever.
        given(capabilitySkillService.page(any(), any()))
                .willReturn(pageOf(List.of(copy(1L, 10L), copy(2L, 10L))))
                .willReturn(new Page<>());
        given(capabilitySkillService.syncWithOutcome(any()))
                .willThrow(new RuntimeException("boom"))
                .willReturn(SkillSyncOutcome.UPDATED);

        runner.runSync(SkillSyncTriggerEnum.TRIGGER_MANUAL);

        verify(skillSyncRunMapper).markSuccess(eq(RECORD_ID), eq("SUCCESS"),
                eq(2), eq(2), eq(1), eq(1), eq(BATCH_SIZE),
                org.mockito.ArgumentMatchers.contains("ERROR: boom"),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("异步提交：请求线程插记录并立刻返回，后台任务已提交")
    void submitSync_returnsImmediately() {
        SkillSyncRunRowVO row = new SkillSyncRunRowVO();
        row.setId(RECORD_ID);
        row.setStatus("RUNNING");
        when(skillSyncRunMapper.getRun(eq(RECORD_ID), any(LocalDateTime.class))).thenReturn(row);

        SkillSyncRunVO run = runner.submitSync(SkillSyncTriggerEnum.TRIGGER_MANUAL);

        assertEquals(RECORD_ID, run.getId());
        verify(skillSyncRunMapper).insertRunning(eq(SkillSyncTriggerEnum.TRIGGER_MANUAL), eq("RUNNING"), anyString());
        verify(syncExecutor).execute(any(Runnable.class));
        verify(capabilitySkillService, never()).page(any(), any());
    }

    @Test
    @DisplayName("锁被占用：落 SKIPPED 记录")
    void lockBusy_recordsSkipped() {
        when(lock.tryLock()).thenReturn(false);

        runner.runSync(SkillSyncTriggerEnum.TRIGGER_SCHEDULED);

        verify(skillSyncRunMapper).markSkipped(eq(RECORD_ID), eq("SKIPPED"),
                org.mockito.ArgumentMatchers.contains("already running"),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
        verify(skillSyncRunMapper, never()).markSuccess(any(), anyString(), anyInt(), anyInt(),
                anyInt(), anyInt(), any(), any(), any(Long.class), anyString());
    }

    @Test
    @DisplayName("总开关关闭：落 SKIPPED 且不扫描")
    void disabled_recordsSkipped() {
        when(systemConfigSpi.get(eq(SystemConfigKeys.SKILL_AUTO_UPDATE_ENABLED), anyString()))
                .thenReturn("false");

        runner.runSync(SkillSyncTriggerEnum.TRIGGER_SCHEDULED);

        verify(skillSyncRunMapper).markSkipped(eq(RECORD_ID), eq("SKIPPED"),
                org.mockito.ArgumentMatchers.contains("skill.auto-update-enabled"),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
        verify(capabilitySkillService, never()).page(any(), any());
    }

    @Test
    @DisplayName("执行中异常：落 FAILED 记录且异常继续上抛")
    void failure_recordsAndRethrows() {
        given(capabilitySkillService.page(any(), any())).willThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> runner.runSync(SkillSyncTriggerEnum.TRIGGER_SCHEDULED));

        verify(skillSyncRunMapper).markFailed(eq(RECORD_ID), eq("FAILED"), eq("db down"),
                org.mockito.ArgumentMatchers.anyLong(), anyString());
        verify(lock).unlock();
    }

    @Test
    @DisplayName("记录表自身按保留天数自清理，且排除本轮刚写的记录")
    void prunesOldRunRecords() {
        given(capabilitySkillService.page(any(), any())).willReturn(new Page<>());
        when(skillSyncRunMapper.deleteOlderThan(any(LocalDateTime.class), any())).thenReturn(7);

        runner.runSync(SkillSyncTriggerEnum.TRIGGER_SCHEDULED);

        verify(skillSyncRunMapper).deleteOlderThan(any(LocalDateTime.class), eq(RECORD_ID));
    }
}

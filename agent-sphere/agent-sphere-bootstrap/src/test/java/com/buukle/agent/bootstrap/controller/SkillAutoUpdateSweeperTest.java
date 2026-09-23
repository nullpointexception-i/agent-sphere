package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.capability.skill.service.impl.SkillAutoUpdateSweeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** skill 自动更新扫描：分页批次处理 + 创建时间窗口过滤。 */
@ExtendWith(MockitoExtension.class)
class SkillAutoUpdateSweeperTest {

    @Mock
    CapabilitySkillService capabilitySkillService;

    SkillAutoUpdateSweeper sweeper;

    @BeforeEach
    void setUp() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CapabilitySkill.class);
        sweeper = new SkillAutoUpdateSweeper(capabilitySkillService, 7, 2);
    }

    private CapabilitySkill copy(Long id, Long originId) {
        CapabilitySkill s = new CapabilitySkill();
        s.setId(id);
        s.setOriginSkillId(originId);
        s.setCreatedAt(LocalDateTime.now().minusDays(1));
        return s;
    }

    @Test
    void sweep_pagesUntilShortPage() {
        // 第一页满批（2 条），第二页不足批（1 条）→ 只处理两页并停止
        given(capabilitySkillService.page(any(), any()))
                .willReturn(pageOf(List.of(copy(1L, 10L), copy(2L, 10L))))
                .willReturn(pageOf(List.of(copy(3L, 11L))));
        given(capabilitySkillService.syncFromOrigin(any())).willReturn(true);

        sweeper.sweep();

        verify(capabilitySkillService, times(2)).page(any(), any());
        verify(capabilitySkillService, times(3)).syncFromOrigin(any());
    }

    @Test
    void sweep_stopsOnEmptyPage() {
        given(capabilitySkillService.page(any(), any())).willReturn(new Page<>());

        sweeper.sweep();

        verify(capabilitySkillService, times(1)).page(any(), any());
        verify(capabilitySkillService, times(0)).syncFromOrigin(any());
    }

    @Test
    void sweep_wrapperHasWindowAndFlags() {
        given(capabilitySkillService.page(any(), any())).willReturn(new Page<>());

        sweeper.sweep();

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(capabilitySkillService).page(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("created_at"), "应含 created_at, 实际: " + sql);
        assertTrue(sql.toLowerCase().contains("auto_update = ?"), "应限定 auto_update, 实际: " + sql);
        assertTrue(sql.toUpperCase().contains("ORIGIN_SKILL_ID IS NOT NULL"), "应限定已安装副本, 实际: " + sql);
        Object params = captor.getValue().getParamNameValuePairs();
        Object windowParam = ((java.util.Map<?, ?>) params).values().stream()
                .filter(v -> v instanceof LocalDateTime)
                .findFirst()
                .orElse(null);
        assertTrue(windowParam != null, "窗口参数应为 LocalDateTime, 实际: " + params);
    }

    private Page<CapabilitySkill> pageOf(List<CapabilitySkill> rows) {
        Page<CapabilitySkill> p = new Page<>(1, 2, rows.size());
        p.setRecords(rows);
        return p;
    }
}
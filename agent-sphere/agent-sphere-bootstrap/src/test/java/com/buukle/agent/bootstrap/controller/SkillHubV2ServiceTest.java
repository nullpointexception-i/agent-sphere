package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.domain.CapabilitySkillFollow;
import com.buukle.agent.capability.skill.domain.CapabilitySkillHub;
import com.buukle.agent.capability.skill.dtvo.vo.HubSkillVO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.exception.CapabilitySkillErrorCode;
import com.buukle.agent.capability.skill.repository.SkillFollowMapper;
import com.buukle.agent.capability.skill.repository.SkillHubMapper;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.capability.skill.service.converter.CapabilitySkillConverter;
import com.buukle.agent.capability.skill.service.impl.SkillHubServiceImpl;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Tenant Skill Hub v2: publish versioning, follow without a copy, install lineage, ancestor visibility. */
@ExtendWith(MockitoExtension.class)
class SkillHubV2ServiceTest {

    @Mock
    CapabilitySkillService skillService;
    @Mock
    SkillHubMapper hubMapper;
    @Mock
    SkillFollowMapper followMapper;
    @Mock
    TenantScopeSpi tenantScopeSpi;

    SkillHubServiceImpl service;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CapabilitySkill.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CapabilitySkillHub.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CapabilitySkillFollow.class);
        service = new SkillHubServiceImpl(skillService, hubMapper, followMapper,
                tenantScopeSpi, new CapabilitySkillConverter());
        AuthContext.setUsername("alice");
        AuthContext.setUserId(7L);
        TenantContext.set(2L);
        org.mockito.Mockito.lenient().when(tenantScopeSpi.ancestorTenantIds(2L)).thenReturn(List.of(2L, 1L));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantContext.clear();
    }

    private CapabilitySkill skill(Long id, String name, String createdBy, int version) {
        CapabilitySkill s = new CapabilitySkill();
        s.setId(id);
        s.setTenantId(2L);
        s.setName(name);
        s.setDescription("d");
        s.setDefinition("{}");
        s.setStatus("ENABLED");
        s.setVisibility("PRIVATE");
        s.setCreatedBy(createdBy);
        s.setVersion(version);
        return s;
    }

    private CapabilitySkillHub hub(Long id, int version, int sourceVersion) {
        CapabilitySkillHub h = new CapabilitySkillHub();
        h.setId(id);
        h.setTenantId(2L);
        h.setSourceSkillId(10L);
        h.setName("shared");
        h.setDescription("d");
        h.setDefinition("{}");
        h.setVersion(version);
        h.setSourceVersion(sourceVersion);
        h.setInstallCount(0);
        h.setFollowCount(0);
        h.setCreatedBy("alice");
        h.setPublisherUserId(99L);
        return h;
    }

    private void givenEmptyEnrichment() {
        org.mockito.Mockito.lenient().when(followMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(skillService.list(any(Wrapper.class))).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(skillService.listByIds(any())).thenReturn(List.of());
    }

    @Test
    void publish_firstTime_createsHubVersionOne() {
        given(skillService.getById(10L)).willReturn(skill(10L, "shared", "alice", 3));
        given(hubMapper.selectOne(any())).willReturn(null);
        given(hubMapper.insert(any(CapabilitySkillHub.class))).willReturn(1);
        givenEmptyEnrichment();

        HubSkillVO vo = service.publish(10L);

        ArgumentCaptor<CapabilitySkillHub> captor = ArgumentCaptor.forClass(CapabilitySkillHub.class);
        verify(hubMapper).insert(captor.capture());
        CapabilitySkillHub saved = captor.getValue();
        assertEquals(1, saved.getVersion());
        assertEquals(3, saved.getSourceVersion());
        assertEquals(2L, saved.getTenantId());
        assertEquals(10L, saved.getSourceSkillId());
        assertEquals(7L, saved.getPublisherUserId());
        assertEquals(1, vo.getVersion());
    }

    @Test
    void publish_again_alwaysIncrementsVersion() {
        CapabilitySkillHub existing = hub(5L, 1, 3);
        given(skillService.getById(10L)).willReturn(skill(10L, "shared", "alice", 3));
        given(hubMapper.selectOne(any())).willReturn(existing);
        given(hubMapper.updateById(any(CapabilitySkillHub.class))).willReturn(1);
        givenEmptyEnrichment();

        service.publish(10L);

        ArgumentCaptor<CapabilitySkillHub> captor = ArgumentCaptor.forClass(CapabilitySkillHub.class);
        verify(hubMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getVersion());
        verify(hubMapper, never()).insert(any(CapabilitySkillHub.class));
    }

    @Test
    void publish_otherUsersSkill_forbidden() {
        given(skillService.getById(10L)).willReturn(skill(10L, "shared", "bob", 3));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.buukle.agent.common.exception.BizException.class,
                () -> service.publish(10L));
        verify(hubMapper, never()).insert(any(CapabilitySkillHub.class));
    }

    @Test
    void install_createsCopyLinkedToHub() {
        given(hubMapper.selectById(5L)).willReturn(hub(5L, 4, 4));
        given(skillService.count(any(Wrapper.class))).willReturn(0L);
        given(skillService.save(any())).willReturn(true);
        given(hubMapper.update(org.mockito.ArgumentMatchers.<CapabilitySkillHub>isNull(), any(Wrapper.class))).willReturn(1);

        SkillVO vo = service.install(5L, true);

        ArgumentCaptor<CapabilitySkill> captor = ArgumentCaptor.forClass(CapabilitySkill.class);
        verify(skillService).save(captor.capture());
        CapabilitySkill copy = captor.getValue();
        assertEquals(10L, copy.getOriginSkillId());
        assertEquals(5L, copy.getHubSkillId());
        assertEquals(4, copy.getOriginVersion());
        assertTrue(copy.getAutoUpdate());
        assertEquals("alice", copy.getCreatedBy());
        assertEquals("shared", vo.getName());
        // hub install counter
        verify(hubMapper).update(org.mockito.ArgumentMatchers.<CapabilitySkillHub>isNull(), any(Wrapper.class));
    }

    @Test
    void install_publisher_forbidden() {
        CapabilitySkillHub publisherHub = hub(5L, 4, 4);
        publisherHub.setPublisherUserId(7L);
        given(hubMapper.selectById(5L)).willReturn(publisherHub);

        BizException error = assertThrows(BizException.class,
                () -> service.install(5L, false));

        assertEquals(CapabilitySkillErrorCode.SKILL_FORBIDDEN.getCode(),
                error.getErrorCode());
        verify(skillService, never()).save(any());
    }

    @Test
    void follow_insertsSubscriptionAndCounts() {
        given(hubMapper.selectById(5L)).willReturn(hub(5L, 1, 1));
        given(followMapper.selectOne(any())).willReturn(null);
        given(followMapper.insert(any(CapabilitySkillFollow.class))).willReturn(1);
        given(hubMapper.update(org.mockito.ArgumentMatchers.<CapabilitySkillHub>isNull(), any(Wrapper.class))).willReturn(1);

        service.follow(5L);

        ArgumentCaptor<CapabilitySkillFollow> captor = ArgumentCaptor.forClass(CapabilitySkillFollow.class);
        verify(followMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals(5L, captor.getValue().getHubSkillId());
        assertEquals(2L, captor.getValue().getTenantId());
        // Follow never creates a local skill copy.
        verify(skillService, never()).save(any(CapabilitySkill.class));
    }

    @Test
    void pageHub_queriesCurrentTenantAndAncestors() {
        Page<CapabilitySkillHub> page = new Page<>(1, 10);
        page.setRecords(List.of(hub(5L, 1, 1)));
        page.setTotal(1);
        given(hubMapper.selectPage(any(), any())).willReturn(page);
        givenEmptyEnrichment();

        IPage<HubSkillVO> result = service.pageHubSkills(1, 10, null);

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(hubMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("tenant_id IN"), "hub must be scoped to the tenant chain: " + sql);
        assertEquals(1, result.getRecords().size());
        assertEquals(1, result.getRecords().get(0).getVersion());
        assertFalse(Boolean.TRUE.equals(result.getRecords().get(0).getFollowed()));
    }
}

package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.exception.CapabilitySkillErrorCode;
import com.buukle.agent.capability.skill.repository.SkillMapper;
import com.buukle.agent.capability.skill.service.converter.CapabilitySkillConverter;
import com.buukle.agent.capability.skill.service.impl.CapabilitySkillServiceImpl;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** skill hub：公开/安装/归属校验。 */
@ExtendWith(MockitoExtension.class)
class SkillHubServiceTest {

    @Mock
    SkillMapper skillMapper;

    CapabilitySkillServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new CapabilitySkillServiceImpl(new CapabilitySkillConverter());
        // ServiceImpl.baseMapper 位于父类 CrudRepository（protected，无 setter），反射注入 mock
        java.lang.reflect.Field f =
                com.baomidou.mybatisplus.extension.repository.CrudRepository.class.getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, skillMapper);
        AuthContext.setUsername("alice");
        TenantUtil.stop();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantUtil.stop();
    }

    private CapabilitySkill skill(Long id, String name, String createdBy, String visibility) {
        CapabilitySkill s = new CapabilitySkill();
        s.setId(id);
        s.setName(name);
        s.setDescription("d");
        s.setDefinition("{\"prompt\":\"p\"}");
        s.setStatus("ENABLED");
        s.setVisibility(visibility);
        s.setCreatedBy(createdBy);
        return s;
    }

    @Test
    void setVisibility_ownSkill_ok() {
        given(skillMapper.selectById(1L)).willReturn(skill(1L, "a", "alice", "PRIVATE"));
        given(skillMapper.updateById(any(CapabilitySkill.class))).willReturn(1);

        SkillVO vo = service.setVisibility(1L, "PUBLIC");

        assertEquals("PUBLIC", vo.getVisibility());
        verify(skillMapper).updateById(any(CapabilitySkill.class));
    }

    @Test
    void setVisibility_othersSkill_forbidden() {
        given(skillMapper.selectById(1L)).willReturn(skill(1L, "a", "bob", "PRIVATE"));

        BizException e = assertThrows(BizException.class, () -> service.setVisibility(1L, "PUBLIC"));
        assertEquals(CapabilitySkillErrorCode.SKILL_FORBIDDEN.getCode(), e.getErrorCode());
        verify(skillMapper, never()).updateById(any(CapabilitySkill.class));
    }

    @Test
    void setVisibility_invalidValue_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.setVisibility(1L, "OPEN"));
    }

    @Test
    void installSkill_publicSource_copiesToSelf() {
        given(skillMapper.selectById(7L)).willReturn(skill(7L, "shared", "bob", "PUBLIC"));
        given(skillMapper.insert(any(CapabilitySkill.class))).willReturn(1);
        given(skillMapper.update(org.mockito.ArgumentMatchers.<CapabilitySkill>isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).willReturn(1);

        SkillVO vo = service.installSkill(7L, null);

        assertEquals("shared", vo.getName());
        assertEquals("PRIVATE", vo.getVisibility());
        assertEquals(7L, vo.getOriginSkillId());
        org.mockito.ArgumentCaptor<CapabilitySkill> captor =
                org.mockito.ArgumentCaptor.forClass(CapabilitySkill.class);
        verify(skillMapper).insert(captor.capture());
        assertEquals("alice", captor.getValue().getCreatedBy());
        assertEquals(7L, captor.getValue().getOriginSkillId());
        // 源行 install_count + 1
        verify(skillMapper).update(org.mockito.ArgumentMatchers.<CapabilitySkill>isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void installSkill_privateSource_forbidden() {
        given(skillMapper.selectById(7L)).willReturn(skill(7L, "secret", "bob", "PRIVATE"));

        BizException e = assertThrows(BizException.class, () -> service.installSkill(7L, null));
        assertEquals(CapabilitySkillErrorCode.SKILL_NOT_PUBLIC.getCode(), e.getErrorCode());
        verify(skillMapper, never()).insert(any(CapabilitySkill.class));
    }

    @Test
    void installSkill_sameName_autoSuffix() {
        given(skillMapper.selectById(7L)).willReturn(skill(7L, "shared", "bob", "PUBLIC"));
        given(skillMapper.insert(any(CapabilitySkill.class))).willReturn(1);
        given(skillMapper.update(org.mockito.ArgumentMatchers.<CapabilitySkill>isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).willReturn(1);
        // existsByName: 第一次命中（同名已存在），第二次放行
        given(skillMapper.selectCount(any())).willReturn(1L).willReturn(0L);

        SkillVO vo = service.installSkill(7L, null);

        assertEquals("shared (1)", vo.getName());
    }

    @Test
    void updateSkill_othersSkill_forbidden() {
        given(skillMapper.selectById(1L)).willReturn(skill(1L, "a", "bob", "PRIVATE"));
        com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO dto =
                new com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO();
        dto.setName("a");
        dto.setDefinition("{\"prompt\":\"p\"}");

        BizException e = assertThrows(BizException.class, () -> service.updateSkill(1L, dto));
        assertEquals(CapabilitySkillErrorCode.SKILL_FORBIDDEN.getCode(), e.getErrorCode());
    }

    @Test
    void deleteSkill_othersSkill_forbidden() {
        given(skillMapper.selectById(1L)).willReturn(skill(1L, "a", "bob", "PRIVATE"));

        BizException e = assertThrows(BizException.class, () -> service.deleteSkill(1L));
        assertEquals(CapabilitySkillErrorCode.SKILL_FORBIDDEN.getCode(), e.getErrorCode());
        verify(skillMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void superAdmin_canPublishOthersSkill() {
        AuthContext.setSuperAdmin(true);
        try {
            given(skillMapper.selectById(1L)).willReturn(skill(1L, "a", "bob", "PRIVATE"));
            given(skillMapper.updateById(any(CapabilitySkill.class))).willReturn(1);

            SkillVO vo = service.setVisibility(1L, "PUBLIC");

            assertEquals("PUBLIC", vo.getVisibility());
            assertTrue(true);
        } finally {
            AuthContext.setSuperAdmin(false);
        }
    }

    @Test
    void hub_showsAllPublic_includingOwn() throws Exception {
        com.baomidou.mybatisplus.core.metadata.IPage<CapabilitySkill> empty =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        given(skillMapper.selectPage(any(), any())).willReturn(empty);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);

        service.pageHubSkills(1, 10, "x");

        verify(skillMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("visibility = ?"), "hub 应只展示公开技能, 实际: " + sql);
        assertTrue(sql.contains("created_by IS NOT NULL"), "hub 应展示所有含作者的公开技能(含自己), 实际: " + sql);
        assertTrue(!sql.contains(" OR "), "hub 不应包含“自己的全部”分支, 实际: " + sql);
        assertTrue(sql.contains("name LIKE"), "keyword 过滤应生效, 实际: " + sql);
        assertTrue(!sql.contains("created_by <> ?"), "hub 不应排除自己, 实际: " + sql);
        Object values = captor.getValue().getParamNameValuePairs();
        assertTrue(values.toString().contains("PUBLIC"), "visibility 参数应为 PUBLIC, 实际: " + values);
    }
}

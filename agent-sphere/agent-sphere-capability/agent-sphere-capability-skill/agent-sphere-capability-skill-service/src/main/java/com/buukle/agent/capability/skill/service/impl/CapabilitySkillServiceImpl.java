package com.buukle.agent.capability.skill.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.enums.SkillCapabilityEnum;
import com.buukle.agent.capability.skill.dtvo.enums.SkillVisibilityEnum;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.exception.CapabilitySkillErrorCode;
import com.buukle.agent.capability.skill.repository.SkillMapper;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.capability.skill.service.converter.CapabilitySkillConverter;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Primary
public class CapabilitySkillServiceImpl extends ServiceImpl<SkillMapper, CapabilitySkill> implements CapabilitySkillService {
    private final CapabilitySkillConverter capabilitySkillConverter;

    @Override
    public SkillVO createSkill(CreateSkillDTO dto) {
        return createSkill(dto, null);
    }

    @Override
    public SkillVO createSkill(CreateSkillDTO dto, String createdBy) {
        validateDefinition(dto.getDefinition());
        CapabilitySkill skill = capabilitySkillConverter.toDO(dto);
        if (createdBy != null && !createdBy.isBlank()) {
            // 显式指定创建人：MetaObjectHandler 的 fillStrategy 不会覆盖非空值
            skill.setCreatedBy(createdBy);
            skill.setUpdatedBy(createdBy);
        }
        save(skill);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public SkillVO getSkill(Long id) {
        CapabilitySkill skill = getById(id);
        if (skill == null) throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public SkillVO updateSkill(Long id, CreateSkillDTO dto) {
        return updateSkill(id, dto, null);
    }

    @Override
    public SkillVO updateSkill(Long id, CreateSkillDTO dto, String updatedBy) {
        validateDefinition(dto.getDefinition());
        CapabilitySkill existing = getById(id);
        if (existing == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        requireOwnership(existing);
        CapabilitySkill skill = capabilitySkillConverter.toDO(dto);
        skill.setId(id);
        // 保留原状态：更新内容不应把 DISABLED 重置为 ENABLED
        skill.setStatus(existing.getStatus());
        if (updatedBy != null && !updatedBy.isBlank()) {
            // 显式指定更新人：MetaObjectHandler 的 fillStrategy 不会覆盖非空值
            skill.setUpdatedBy(updatedBy);
        }
        updateById(skill);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public void deleteSkill(Long id) {
        CapabilitySkill existing = getById(id);
        if (existing == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        requireOwnership(existing);
        removeById(id);
    }

    @Override
    public void batchDeleteSkill(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (Long id : ids) {
            CapabilitySkill existing = getById(id);
            if (existing == null) {
                throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
            }
            requireOwnership(existing);
        }
        removeByIds(ids);
    }

    @Override
    public List<SkillVO> listSkills(String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        log.warn("listSkills called: keyword='{}', startTime={}, endTime={}", keyword, startTime, endTime);
        List<CapabilitySkill> list = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilitySkill::getName, keyword)
                .ge(startTime != null, CapabilitySkill::getCreatedAt, startTime)
                .le(endTime != null, CapabilitySkill::getCreatedAt, endTime)
                .orderByDesc(CapabilitySkill::getCreatedAt)
                .list();
        log.warn("listSkills result: {} rows", list.size());
        return list.stream().map(capabilitySkillConverter::toVO).toList();
    }

    @Override
    public IPage<SkillVO> pageSkills(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime) {
        Page<CapabilitySkill> p = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CapabilitySkill::getName, keyword)
                .ge(startTime != null, CapabilitySkill::getCreatedAt, startTime)
                .le(endTime != null, CapabilitySkill::getCreatedAt, endTime)
                .orderByDesc(CapabilitySkill::getCreatedAt)
                .page(new Page<>(page, size));
        return p.convert(capabilitySkillConverter::toVO);
    }

    @Override
    public SkillVO updateStatus(Long id, String status) {
        CapabilitySkill skill = getById(id);
        if (skill == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        requireOwnership(skill);
        SkillCapabilityEnum.assertValidStatus(status);
        skill.setStatus(status);
        updateById(skill);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public void batchUpdateStatus(java.util.List<Long> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        SkillCapabilityEnum.assertValidStatus(status);
        for (Long id : ids) {
            CapabilitySkill existing = getById(id);
            if (existing == null) {
                throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
            }
            requireOwnership(existing);
        }
        lambdaUpdate().in(CapabilitySkill::getId, ids).set(CapabilitySkill::getStatus, status).update();
    }

    @Override
    public List<SkillVO> listSkillsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return lambdaQuery().in(CapabilitySkill::getId, ids).list().stream().map(capabilitySkillConverter::toVO).toList();
    }

    @Override
    public SkillVO setVisibility(Long id, String visibility) {
        SkillVisibilityEnum.assertValidVisibility(visibility);
        CapabilitySkill skill = getById(id);
        if (skill == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        requireOwnership(skill);
        skill.setVisibility(visibility);
        updateById(skill);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public SkillVO installSkill(Long sourceId, String newName) {
        String operator = AuthContext.getUsername();
        // 提权读源（绕过行级隔离），再手动校验可见性；读完恢复原租户
        String savedTenant = TenantUtil.get();
        CapabilitySkill source;
        try {
            TenantUtil.stop();
            source = getById(sourceId);
        } finally {
            if (savedTenant != null && !savedTenant.isBlank()) {
                TenantUtil.start(savedTenant);
            }
        }
        if (source == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        boolean isOwner = java.util.Objects.equals(operator, source.getCreatedBy());
        if (!SkillVisibilityEnum.PUBLIC.equals(source.getVisibility()) && !isOwner && !AuthContext.isSuperAdmin()) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_PUBLIC);
        }
        String name = (newName != null && !newName.isBlank()) ? newName.trim() : source.getName();
        name = resolveCopyName(name, operator);
        CapabilitySkill copy = new CapabilitySkill();
        copy.setName(name);
        copy.setDescription(source.getDescription());
        copy.setDefinition(source.getDefinition());
        copy.setStatus(SkillCapabilityEnum.STATUS_ENABLED);
        copy.setVisibility(SkillVisibilityEnum.PRIVATE);
        copy.setOriginSkillId(source.getId());
        copy.setInstallCount(0);
        copy.setCreatedBy(operator);
        copy.setUpdatedBy(operator);
        save(copy);
        lambdaUpdate().eq(CapabilitySkill::getId, source.getId())
                .setSql("install_count = COALESCE(install_count, 0) + 1").update();
        return capabilitySkillConverter.toVO(copy);
    }

    @Override
    public IPage<SkillVO> pageHubSkills(int page, int size, String keyword) {
        // hub = 所有用户（含自己）的公开技能：跨用户公开列表，不受行级归属过滤收窄。
        // WHERE 显式引用 created_by（恒真条件），DataPermissionInterceptor 会整体跳过改写，勿删条件。
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CapabilitySkill> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.like(keyword != null && !keyword.isBlank(), "name", keyword);
        qw.eq("visibility", SkillVisibilityEnum.PUBLIC);
        qw.isNotNull("created_by");
        qw.orderByDesc("install_count").orderByDesc("created_at");
        Page<CapabilitySkill> p = getBaseMapper().selectPage(new Page<>(page, size), qw);
        return p.convert(capabilitySkillConverter::toVO);
    }

    /** 同名自动加后缀（hub 安装友好）；上限防极端循环。 */
    private String resolveCopyName(String base, String operator) {
        String name = base.length() > 56 ? base.substring(0, 56) : base;
        if (!existsByName(name, operator)) {
            return name.length() > 64 ? name.substring(0, 64) : name;
        }
        for (int n = 1; n < 10000; n++) {
            String candidate = base + " (" + n + ")";
            if (candidate.length() > 64) {
                candidate = base.substring(0, Math.max(0, 64 - (" (" + n + ")").length())) + " (" + n + ")";
            }
            if (!existsByName(candidate, operator)) {
                return candidate;
            }
        }
        throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, "重名过多，换个名字再安装");
    }

    private boolean existsByName(String name, String operator) {
        // 注意：不用 lambdaQuery()（其内部 getMapperClass 需要真实 MyBatis 代理，
        // 且此处只需按名字判重）；显式 QueryWrapper 同样受逻辑删除过滤
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CapabilitySkill> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.eq("name", name);
        return getBaseMapper().selectCount(qw) > 0;
    }

    /** 非超管只能写自己创建的 skill（抄 InstanceServiceImpl.requireOwnership）。 */
    private void requireOwnership(CapabilitySkill skill) {
        if (AuthContext.isSuperAdmin()) {
            return;
        }
        String owner = AuthContext.getUsername();
        if (!java.util.Objects.equals(owner, skill.getCreatedBy())) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_FORBIDDEN, "无权操作他人Skill");
        }
    }

    /** 创建/更新时校验 definition（非法给出明确错误，不再静默丢弃）。 */
    private void validateDefinition(String definition) {
        if (definition == null || definition.isBlank()) {
            return;
        }
        try {
            com.buukle.agent.common.skill.SkillDefinitionParser.parse(definition);
        } catch (InvalidSubRunDefinition e) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}

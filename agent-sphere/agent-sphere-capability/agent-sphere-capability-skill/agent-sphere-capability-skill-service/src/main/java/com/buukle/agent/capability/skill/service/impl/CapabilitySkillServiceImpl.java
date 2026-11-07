package com.buukle.agent.capability.skill.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.dtvo.dto.CreateSkillDTO;
import com.buukle.agent.capability.skill.dtvo.enums.SkillCapabilityEnum;
import com.buukle.agent.capability.skill.dtvo.enums.SkillSyncOutcome;
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

    /** Operator used when auto-update syncs origin content (background tasks have no user context). */
    private static final String AUTO_UPDATE_OPERATOR = "skill-auto-update";

    @Override
    public SkillVO createSkill(CreateSkillDTO dto) {
        return createSkill(dto, null);
    }

    @Override
    public SkillVO createSkill(CreateSkillDTO dto, String createdBy) {
        validateDefinition(dto.getDefinition());
        CapabilitySkill skill = capabilitySkillConverter.toDO(dto);
        skill.setVersion(1);
        if (createdBy != null && !createdBy.isBlank()) {
            // Explicit creator: MetaObjectHandler's fillStrategy does not overwrite non-null values
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
        // Preserve the existing status: updating content must not reset DISABLED back to ENABLED
        skill.setStatus(existing.getStatus());
        // A content update counts as a new version: installed copies compare origin_version against it to trigger auto-update
        skill.setVersion(existing.getVersion() != null ? existing.getVersion() + 1 : 1);
        if (updatedBy != null && !updatedBy.isBlank()) {
            // Explicit updater: MetaObjectHandler's fillStrategy does not overwrite non-null values
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
        // Privileged read of the source (bypasses row-level isolation), then validate visibility
        // manually; restore the original tenant afterwards
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
        copy.setVersion(1);
        copy.setOriginVersion(source.getVersion() != null ? source.getVersion() : 1);
        copy.setAutoUpdate(false);
        // Show "from origin vN / forked at this moment" immediately after install, instead of waiting for the first sync
        copy.setSyncedAt(LocalDateTime.now());
        copy.setSyncedFromVersion(source.getVersion() != null ? source.getVersion() : 1);
        copy.setCreatedBy(operator);
        copy.setUpdatedBy(operator);
        save(copy);
        lambdaUpdate().eq(CapabilitySkill::getId, source.getId())
                .setSql("install_count = COALESCE(install_count, 0) + 1").update();
        return capabilitySkillConverter.toVO(copy);
    }

    @Override
    public IPage<SkillVO> pageHubSkills(int page, int size, String keyword) {
        // hub = public skills of all users (including the caller): a cross-user public list that is
        // not narrowed by row-level ownership filtering.
        // The WHERE clause references created_by explicitly (an always-true condition) so
        // DataPermissionInterceptor skips rewriting it entirely - do not remove the condition.
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CapabilitySkill> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.like(keyword != null && !keyword.isBlank(), "name", keyword);
        qw.eq("visibility", SkillVisibilityEnum.PUBLIC);
        qw.isNotNull("created_by");
        qw.orderByDesc("install_count").orderByDesc("created_at");
        Page<CapabilitySkill> p = getBaseMapper().selectPage(new Page<>(page, size), qw);
        return p.convert(capabilitySkillConverter::toVO);
    }

    @Override
    public SkillVO setAutoUpdate(Long id, boolean enabled) {
        CapabilitySkill skill = getById(id);
        if (skill == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        requireOwnership(skill);
        if (skill.getOriginSkillId() == null) {
            // Only installed (forked) copies have an origin to follow; enabling auto-update on an original skill is meaningless
            throw new BizException(CapabilitySkillErrorCode.SKILL_FORBIDDEN, "Original skills do not support auto-update");
        }
        skill.setAutoUpdate(enabled);
        updateById(skill);
        return capabilitySkillConverter.toVO(skill);
    }

    @Override
    public SkillSyncOutcome syncWithOutcome(CapabilitySkill copy) {
        CapabilitySkill source = getById(copy.getOriginSkillId());
        if (source == null) {
            // Origin deleted: the copy stays on old content with no alert at all - only this return value surfaces it in the execution record
            return SkillSyncOutcome.SOURCE_GONE;
        }
        if (!SkillVisibilityEnum.PUBLIC.equals(source.getVisibility())) {
            return SkillSyncOutcome.NOT_PUBLIC;
        }
        int sourceVersion = source.getVersion() != null ? source.getVersion() : 1;
        int originVersion = copy.getOriginVersion() != null ? copy.getOriginVersion() : 1;
        if (sourceVersion <= originVersion) {
            return SkillSyncOutcome.VERSION_NOT_AHEAD;
        }
        // Conditional update: only overwrite when origin_version has not already been advanced by
        // another copy, keeping multiple copies idempotent.
        // A successful sync also writes the synced_at / synced_from_version display snapshots -
        // they always match origin_version in value, but once the conditional update fails,
        // origin_version desyncs from what the UI shows.
        boolean updated = lambdaUpdate()
                .eq(CapabilitySkill::getId, copy.getId())
                .eq(CapabilitySkill::getOriginVersion, copy.getOriginVersion())
                .set(CapabilitySkill::getName, source.getName())
                .set(CapabilitySkill::getDescription, source.getDescription())
                .set(CapabilitySkill::getDefinition, source.getDefinition())
                .set(CapabilitySkill::getOriginVersion, sourceVersion)
                .set(CapabilitySkill::getSyncedAt, LocalDateTime.now())
                .set(CapabilitySkill::getSyncedFromVersion, sourceVersion)
                .set(CapabilitySkill::getUpdatedBy, AUTO_UPDATE_OPERATOR)
                .update();
        return updated ? SkillSyncOutcome.UPDATED : SkillSyncOutcome.CONCURRENT_UPDATE;
    }

    @Override
    public boolean syncFromOrigin(CapabilitySkill copy) {
        return syncWithOutcome(copy) == SkillSyncOutcome.UPDATED;
    }

    /** Appends a suffix automatically when the name collides (friendlier hub installs); the cap guards against extreme loops. */
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
        throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, "Too many name collisions; choose another name and install again");
    }

    private boolean existsByName(String name, String operator) {
        // Note: lambdaQuery() is not used here (it needs a real MyBatis proxy in getMapperClass,
        // and here we only need to check duplicates by name); an explicit QueryWrapper is also
        // filtered by logical delete
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CapabilitySkill> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.eq("name", name);
        return getBaseMapper().selectCount(qw) > 0;
    }

    /** Non-super-admins may only write skills they created (mirrors InstanceServiceImpl.requireOwnership). */
    private void requireOwnership(CapabilitySkill skill) {
        if (AuthContext.isSuperAdmin()) {
            return;
        }
        String owner = AuthContext.getUsername();
        if (!java.util.Objects.equals(owner, skill.getCreatedBy())) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_FORBIDDEN, "Not allowed to modify another user's skill");
        }
    }

    /** Validates definition on create/update (invalid input yields a clear error instead of being silently dropped). */
    private void validateDefinition(String definition) {
        // Shares the canonical validation in common: JSON shape + promptTemplate structure (# / ## stepN / ### stepN.M).
        // Both the UI form and the agent skill tool go through this service, so this is the single enforcement point.
        String error = com.buukle.agent.common.skill.SkillTemplateValidator.validate(definition);
        if (error != null) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID, error);
        }
    }
}

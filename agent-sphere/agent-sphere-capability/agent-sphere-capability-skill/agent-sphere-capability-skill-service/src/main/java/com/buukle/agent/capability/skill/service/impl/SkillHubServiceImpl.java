package com.buukle.agent.capability.skill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.capability.skill.domain.CapabilitySkill;
import com.buukle.agent.capability.skill.domain.CapabilitySkillFollow;
import com.buukle.agent.capability.skill.domain.CapabilitySkillHub;
import com.buukle.agent.capability.skill.dtvo.enums.SkillCapabilityEnum;
import com.buukle.agent.capability.skill.dtvo.enums.SkillVisibilityEnum;
import com.buukle.agent.capability.skill.dtvo.vo.HubPublishStateVO;
import com.buukle.agent.capability.skill.dtvo.vo.HubSkillVO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.exception.CapabilitySkillErrorCode;
import com.buukle.agent.capability.skill.repository.SkillFollowMapper;
import com.buukle.agent.capability.skill.repository.SkillHubMapper;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
import com.buukle.agent.capability.skill.service.SkillHubService;
import com.buukle.agent.capability.skill.service.converter.CapabilitySkillConverter;
import com.buukle.agent.capability.skill.service.support.SkillCopyName;
import com.buukle.agent.capability.skill.spi.SkillHubSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tenant-scoped Skill Hub.
 *
 * <p>Visibility is the current tenant plus every ancestor tenant, so a child tenant sees the
 * platform tenant's hub (the platform-wide library) without any extra sharing switch.
 *
 * <p>Hub versions only increase: every publish call bumps the hub version by one, including a
 * re-publish of older content. There is no withdraw operation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillHubServiceImpl implements SkillHubService, SkillHubSpi {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TENANT_DEPTH = 32;

    private final CapabilitySkillService skillService;
    private final SkillHubMapper hubMapper;
    private final SkillFollowMapper followMapper;
    private final TenantScopeSpi tenantScopeSpi;
    private final CapabilitySkillConverter converter;

    // ==================== publish ====================

    @Override
    @Transactional
    public HubSkillVO publish(Long skillId) {
        CapabilitySkill skill = skillService.getById(skillId);
        if (skill == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        requireOwnership(skill);

        String operator = AuthContext.getUsername();
        Long tenantId = skill.getTenantId() != null ? skill.getTenantId() : currentTenantId();
        CapabilitySkillHub hub = findBySource(tenantId, skillId);
        boolean created = hub == null;
        if (created) {
            hub = new CapabilitySkillHub();
            hub.setTenantId(tenantId);
            hub.setSourceSkillId(skillId);
            hub.setVersion(1);
            hub.setInstallCount(0);
            hub.setFollowCount(0);
            hub.setCreatedBy(operator);
        } else {
            // Every publish bumps the version, even when content is restored from an older local version.
            hub.setVersion(next(hub.getVersion()));
        }
        hub.setPublisherUserId(AuthContext.getUserId());
        hub.setName(skill.getName());
        hub.setDescription(skill.getDescription());
        hub.setDefinition(skill.getDefinition());
        hub.setSourceVersion(skill.getVersion());
        hub.setUpdatedBy(operator);
        if (created) {
            hubMapper.insert(hub);
        } else {
            hubMapper.updateById(hub);
        }
        return enrich(List.of(hub)).get(0);
    }

    @Override
    public HubPublishStateVO publishState(Long skillId) {
        CapabilitySkill skill = skillService.getById(skillId);
        if (skill == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        Long tenantId = skill.getTenantId() != null ? skill.getTenantId() : currentTenantId();
        CapabilitySkillHub hub = findBySource(tenantId, skillId);

        HubPublishStateVO state = new HubPublishStateVO();
        state.setSkillId(skillId);
        state.setPublished(hub != null);
        state.setLocalVersion(skill.getVersion());
        if (hub != null) {
            state.setHubSkillId(hub.getId());
            state.setHubVersion(hub.getVersion());
            state.setHasUnpublished(versionOf(skill.getVersion()) > versionOf(hub.getSourceVersion()));
        } else {
            state.setHasUnpublished(false);
        }
        return state;
    }

    // ==================== browse ====================

    @Override
    public IPage<HubSkillVO> pageHubSkills(int page, int size, String keyword) {
        Page<CapabilitySkillHub> p = new Page<>(Math.max(1, page), Math.max(1, size));
        LambdaQueryWrapper<CapabilitySkillHub> qw = Wrappers.<CapabilitySkillHub>lambdaQuery()
                .in(CapabilitySkillHub::getTenantId, hubTenantIds())
                .like(keyword != null && !keyword.isBlank(), CapabilitySkillHub::getName,
                        keyword == null ? null : keyword.trim())
                .orderByDesc(CapabilitySkillHub::getUpdatedAt)
                .orderByDesc(CapabilitySkillHub::getId);
        IPage<CapabilitySkillHub> rows = hubMapper.selectPage(p, qw);
        List<HubSkillVO> vos = enrich(rows.getRecords());
        Page<HubSkillVO> result = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        result.setRecords(vos);
        return result;
    }

    @Override
    public HubSkillVO getHub(Long hubId) {
        CapabilitySkillHub hub = requireVisibleHub(hubId);
        return enrich(List.of(hub)).get(0);
    }

    @Override
    public List<HubSkillVO> listFollowed() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return List.of();
        }
        List<Long> hubIds = followMapper.selectList(Wrappers.<CapabilitySkillFollow>lambdaQuery()
                        .eq(CapabilitySkillFollow::getUserId, userId))
                .stream().map(CapabilitySkillFollow::getHubSkillId).distinct().toList();
        if (hubIds.isEmpty()) {
            return List.of();
        }
        List<HubSkillVO> vos = enrich(hubMapper.selectBatchIds(hubIds));
        return vos.stream()
                .sorted((a, b) -> Long.compare(
                        b.getId() == null ? 0L : b.getId(), a.getId() == null ? 0L : a.getId()))
                .toList();
    }

    // ==================== follow / install ====================

    @Override
    @Transactional
    public void follow(Long hubId) {
        CapabilitySkillHub hub = requireVisibleHub(hubId);
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        CapabilitySkillFollow existing = followMapper.selectOne(
                Wrappers.<CapabilitySkillFollow>lambdaQuery()
                        .eq(CapabilitySkillFollow::getUserId, userId)
                        .eq(CapabilitySkillFollow::getHubSkillId, hubId)
                        .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        CapabilitySkillFollow follow = new CapabilitySkillFollow();
        follow.setTenantId(currentTenantId());
        follow.setUserId(userId);
        follow.setHubSkillId(hubId);
        follow.setCreatedAt(LocalDateTime.now());
        followMapper.insert(follow);
        hubMapper.update(null, Wrappers.<CapabilitySkillHub>lambdaUpdate()
                .eq(CapabilitySkillHub::getId, hub.getId())
                .setSql("follow_count = COALESCE(follow_count, 0) + 1"));
    }

    @Override
    @Transactional
    public void unfollow(Long hubId) {
        CapabilitySkillHub hub = requireVisibleHub(hubId);
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return;
        }
        int removed = followMapper.delete(Wrappers.<CapabilitySkillFollow>lambdaQuery()
                .eq(CapabilitySkillFollow::getUserId, userId)
                .eq(CapabilitySkillFollow::getHubSkillId, hubId));
        if (removed > 0) {
            hubMapper.update(null, Wrappers.<CapabilitySkillHub>lambdaUpdate()
                    .eq(CapabilitySkillHub::getId, hub.getId())
                    .setSql("follow_count = GREATEST(COALESCE(follow_count, 0) - 1, 0)"));
        }
    }

    @Override
    @Transactional
    public SkillVO install(Long hubId, Boolean autoUpdate) {
        CapabilitySkillHub hub = requireVisibleHub(hubId);
        if (isPublisher(hub)) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_FORBIDDEN,
                    "Publisher cannot install their own hub skill");
        }
        String operator = AuthContext.getUsername();
        String name = SkillCopyName.resolve(hub.getName(),
                candidate -> countByName(candidate) > 0);
        int hubVersion = versionOf(hub.getVersion());

        CapabilitySkill copy = new CapabilitySkill();
        copy.setName(name);
        copy.setDescription(hub.getDescription());
        copy.setDefinition(hub.getDefinition());
        copy.setStatus(SkillCapabilityEnum.STATUS_ENABLED);
        copy.setVisibility(SkillVisibilityEnum.PRIVATE);
        copy.setOriginSkillId(hub.getSourceSkillId());
        copy.setHubSkillId(hub.getId());
        copy.setInstallCount(0);
        copy.setVersion(1);
        copy.setOriginVersion(hubVersion);
        copy.setAutoUpdate(Boolean.TRUE.equals(autoUpdate));
        copy.setSyncedAt(LocalDateTime.now());
        copy.setSyncedFromVersion(hubVersion);
        copy.setCreatedBy(operator);
        copy.setUpdatedBy(operator);
        skillService.save(copy);

        hubMapper.update(null, Wrappers.<CapabilitySkillHub>lambdaUpdate()
                .eq(CapabilitySkillHub::getId, hub.getId())
                .setSql("install_count = COALESCE(install_count, 0) + 1"));
        return converter.toVO(copy);
    }

    // ==================== runtime SPI ====================

    @Override
    public List<SkillVO> listHubSkillsByIds(List<Long> hubIds) {
        if (hubIds == null || hubIds.isEmpty()) {
            return List.of();
        }
        List<CapabilitySkillHub> hubs = hubMapper.selectBatchIds(hubIds);
        List<Long> visibleTenants = runtimeVisibleTenants();
        return hubs.stream()
                .filter(h -> visibleTenants == null || visibleTenants.contains(h.getTenantId()))
                .map(this::toSkillVO)
                .toList();
    }

    @Override
    public SkillVO getHubSkill(Long hubId) {
        CapabilitySkillHub hub = hubId == null ? null : hubMapper.selectById(hubId);
        if (hub == null) {
            return null;
        }
        List<Long> visibleTenants = runtimeVisibleTenants();
        if (visibleTenants != null && !visibleTenants.contains(hub.getTenantId())) {
            return null;
        }
        return toSkillVO(hub);
    }

    /**
     * Runtime visibility check for hub content: the run's tenant plus its ancestors. A null current
     * tenant means a system task without tenant context, which mirrors the row-isolation rule and
     * therefore does not filter.
     */
    private List<Long> runtimeVisibleTenants() {
        Long current = currentTenantId();
        return current == null ? null : hubTenantIds();
    }

    // ==================== helpers ====================

    private CapabilitySkillHub findBySource(Long tenantId, Long skillId) {
        if (tenantId == null || skillId == null) {
            return null;
        }
        return hubMapper.selectOne(Wrappers.<CapabilitySkillHub>lambdaQuery()
                .eq(CapabilitySkillHub::getTenantId, tenantId)
                .eq(CapabilitySkillHub::getSourceSkillId, skillId)
                .last("LIMIT 1"));
    }

    /** Current tenant plus every ancestor: the platform tenant's hub is therefore platform-wide. */
    private List<Long> hubTenantIds() {
        Long current = currentTenantId();
        if (current == null) {
            return List.of(-1L);
        }
        List<Long> ids = tenantScopeSpi.ancestorTenantIds(current);
        return ids == null || ids.isEmpty() ? List.of(current) : ids;
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId != null) {
            return tenantId;
        }
        TenantScope scope = TenantScopeHolder.currentOrFallback();
        return scope == null ? null : scope.currentTenantId();
    }

    /** The publisher of a hub entry may not install a private copy of their own skill. */
    private boolean isPublisher(CapabilitySkillHub hub) {
        Long userId = AuthContext.getUserId();
        if (hub.getPublisherUserId() != null && userId != null) {
            return Objects.equals(userId, hub.getPublisherUserId());
        }
        String username = AuthContext.getUsername();
        CapabilitySkill source = hub.getSourceSkillId() == null
                ? null : skillService.getById(hub.getSourceSkillId());
        return source != null
                ? Objects.equals(username, source.getCreatedBy())
                : Objects.equals(username, hub.getCreatedBy());
    }

    private CapabilitySkillHub requireVisibleHub(Long hubId) {
        if (hubId == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        CapabilitySkillHub hub = hubMapper.selectById(hubId);
        if (hub == null) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        if (AuthContext.isSuperAdmin()) {
            return hub;
        }
        if (!hubTenantIds().contains(hub.getTenantId())) {
            // Do not leak the existence of another tenant's hub entry.
            throw new BizException(CapabilitySkillErrorCode.SKILL_NOT_FOUND);
        }
        return hub;
    }

    private void requireOwnership(CapabilitySkill skill) {
        if (AuthContext.isSuperAdmin()) {
            return;
        }
        if (!Objects.equals(AuthContext.getUsername(), skill.getCreatedBy())) {
            throw new BizException(CapabilitySkillErrorCode.SKILL_FORBIDDEN,
                    "Not allowed to publish another user's skill");
        }
    }

    private long countByName(String name) {
        QueryWrapper<CapabilitySkill> qw = new QueryWrapper<>();
        qw.eq("name", name);
        return skillService.count(qw);
    }

    /** Batched enrichment so the hub list needs no per-row queries. */
    private List<HubSkillVO> enrich(List<CapabilitySkillHub> hubs) {
        if (hubs == null || hubs.isEmpty()) {
            return List.of();
        }
        String username = AuthContext.getUsername();
        Long userId = AuthContext.getUserId();
        Set<Long> hubIds = hubs.stream().map(CapabilitySkillHub::getId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Long> followed = Collections.emptySet();
        if (userId != null && !hubIds.isEmpty()) {
            followed = followMapper.selectList(Wrappers.<CapabilitySkillFollow>lambdaQuery()
                            .eq(CapabilitySkillFollow::getUserId, userId)
                            .in(CapabilitySkillFollow::getHubSkillId, hubIds))
                    .stream().map(CapabilitySkillFollow::getHubSkillId)
                    .collect(Collectors.toSet());
        }

        Map<Long, CapabilitySkill> installed = Collections.emptyMap();
        if (username != null && !hubIds.isEmpty()) {
            installed = skillService.list(Wrappers.<CapabilitySkill>lambdaQuery()
                            .eq(CapabilitySkill::getCreatedBy, username)
                            .in(CapabilitySkill::getHubSkillId, hubIds))
                    .stream()
                    .filter(s -> s.getHubSkillId() != null)
                    .filter(s -> Objects.equals(s.getCreatedBy(), username))
                    .collect(Collectors.toMap(CapabilitySkill::getHubSkillId, Function.identity(),
                            (a, b) -> a));
        }

        List<Long> sourceIds = hubs.stream().map(CapabilitySkillHub::getSourceSkillId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, CapabilitySkill> sources = sourceIds.isEmpty()
                ? Collections.emptyMap()
                : skillService.listByIds(sourceIds).stream()
                        .collect(Collectors.toMap(CapabilitySkill::getId, Function.identity(),
                                (a, b) -> a));

        List<HubSkillVO> result = new ArrayList<>(hubs.size());
        for (CapabilitySkillHub hub : hubs) {
            HubSkillVO vo = toVO(hub);
            CapabilitySkill source = hub.getSourceSkillId() == null ? null
                    : sources.get(hub.getSourceSkillId());
            CapabilitySkill copy = installed.get(hub.getId());
            boolean mine = (hub.getPublisherUserId() != null
                    && Objects.equals(userId, hub.getPublisherUserId()))
                    || (source != null
                        ? Objects.equals(username, source.getCreatedBy())
                        : Objects.equals(username, hub.getCreatedBy()));
            vo.setMine(mine);
            vo.setFollowed(followed.contains(hub.getId()));
            vo.setInstalledSkillId(copy == null ? null : copy.getId());
            vo.setLocalVersion(source == null ? null : source.getVersion());
            vo.setHasUnpublished(source != null
                    && versionOf(source.getVersion()) > versionOf(hub.getSourceVersion()));
            result.add(vo);
        }
        return result;
    }

    private HubSkillVO toVO(CapabilitySkillHub hub) {
        HubSkillVO vo = new HubSkillVO();
        vo.setId(hub.getId());
        vo.setTenantId(hub.getTenantId());
        vo.setSourceSkillId(hub.getSourceSkillId());
        vo.setPublisherUserId(hub.getPublisherUserId());
        vo.setPublisherName(hub.getCreatedBy());
        vo.setName(hub.getName());
        vo.setDescription(hub.getDescription());
        vo.setVersion(hub.getVersion());
        vo.setSourceVersion(hub.getSourceVersion());
        vo.setInstallCount(hub.getInstallCount());
        vo.setFollowCount(hub.getFollowCount());
        vo.setCreatedAt(hub.getCreatedAt() == null ? null : hub.getCreatedAt().format(DTF));
        vo.setUpdatedAt(hub.getUpdatedAt() == null ? null : hub.getUpdatedAt().format(DTF));
        return vo;
    }

    private SkillVO toSkillVO(CapabilitySkillHub hub) {
        SkillVO vo = new SkillVO();
        vo.setId(hub.getId());
        vo.setName(hub.getName());
        vo.setDescription(hub.getDescription());
        vo.setDefinition(hub.getDefinition());
        vo.setStatus(SkillCapabilityEnum.STATUS_ENABLED);
        vo.setVersion(hub.getVersion());
        vo.setCreatedBy(hub.getCreatedBy());
        vo.setUpdatedBy(hub.getUpdatedBy());
        vo.setCreatedAt(hub.getCreatedAt() == null ? null : hub.getCreatedAt().format(DTF));
        vo.setUpdatedAt(hub.getUpdatedAt() == null ? null : hub.getUpdatedAt().format(DTF));
        return vo;
    }

    private static int versionOf(Integer version) {
        return version == null ? 0 : version;
    }

    private static int next(Integer version) {
        return versionOf(version) + 1;
    }
}

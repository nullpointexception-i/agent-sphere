package com.buukle.agent.capability.mcp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.capability.mcp.domain.CapabilityMcpFollow;
import com.buukle.agent.capability.mcp.domain.CapabilityMcpHub;
import com.buukle.agent.capability.mcp.dtvo.enums.McpCapabilityEnum;
import com.buukle.agent.capability.mcp.dtvo.vo.McpHubVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.capability.mcp.repository.McpFollowMapper;
import com.buukle.agent.capability.mcp.repository.McpHubMapper;
import com.buukle.agent.capability.mcp.repository.McpMapper;
import com.buukle.agent.capability.mcp.service.McpHubService;
import com.buukle.agent.capability.mcp.service.converter.CapabilityMcpConverter;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportClient;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportFactory;
import com.buukle.agent.capability.mcp.spi.McpHubSpi;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class McpHubServiceImpl implements McpHubService, McpHubSpi {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final McpMapper mcpMapper;
    private final McpHubMapper hubMapper;
    private final McpFollowMapper followMapper;
    private final TenantScopeSpi tenantScopeSpi;
    private final CapabilityMcpConverter converter;
    private final McpTransportFactory mcpTransportFactory;

    // ==================== publish ====================

    @Override
    @Transactional
    public McpHubVO publish(Long mcpId) {
        CapabilityMcp mcp = mcpMapper.selectById(mcpId);
        if (mcp == null) {
            throw new BizException(CapabilityMcpErrorCode.MCP_NOT_FOUND);
        }
        requireOwnership(mcp);

        String operator = AuthContext.getUsername();
        Long tenantId = mcp.getTenantId() != null ? mcp.getTenantId() : currentTenantId();
        CapabilityMcpHub hub = findBySource(tenantId, mcpId);
        boolean created = hub == null;
        if (created) {
            hub = new CapabilityMcpHub();
            hub.setTenantId(tenantId);
            hub.setSourceMcpId(mcpId);
            hub.setVersion(1);
            hub.setInstallCount(0);
            hub.setFollowCount(0);
            hub.setStatus(STATUS_ACTIVE);
            hub.setCreatedBy(operator);
        } else {
            hub.setVersion(next(hub.getVersion()));
            hub.setStatus(STATUS_ACTIVE);
        }
        hub.setPublisherUserId(AuthContext.getUserId());
        hub.setName(mcp.getName());
        hub.setDescription(mcp.getDescription());
        hub.setServerUrl(mcp.getServerUrl());
        hub.setServerType(mcp.getServerType());
        // Product decision: MCP Hub shares the auth config with the tenant and its descendants.
        hub.setAuthConfig(mcp.getAuthConfig());
        hub.setToolDefinitions(mcp.getToolDefinitions());
        hub.setCompressEnabled(Boolean.TRUE.equals(mcp.getCompressEnabled()));
        hub.setAccessMode(mcp.getAccessMode());
        hub.setSecurityLevel(mcp.getSecurityLevel());
        hub.setApprovalScope(mcp.getApprovalScope());
        hub.setUpdatedBy(operator);
        hub.setUpdatedAt(LocalDateTime.now());
        if (created) {
            hubMapper.insert(hub);
        } else {
            hubMapper.updateById(hub);
        }
        return enrich(List.of(hub)).get(0);
    }

    // ==================== browse ====================

    @Override
    public IPage<McpHubVO> pageHubMcps(int page, int size, String keyword) {
        Page<CapabilityMcpHub> p = new Page<>(Math.max(1, page), Math.max(1, size));
        LambdaQueryWrapper<CapabilityMcpHub> qw = Wrappers.<CapabilityMcpHub>lambdaQuery()
                .in(CapabilityMcpHub::getTenantId, hubTenantIds())
                .eq(CapabilityMcpHub::getStatus, STATUS_ACTIVE)
                .like(keyword != null && !keyword.isBlank(), CapabilityMcpHub::getName,
                        keyword == null ? null : keyword.trim())
                .orderByDesc(CapabilityMcpHub::getUpdatedAt)
                .orderByDesc(CapabilityMcpHub::getId);
        IPage<CapabilityMcpHub> rows = hubMapper.selectPage(p, qw);
        Page<McpHubVO> result = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        result.setRecords(enrich(rows.getRecords()));
        return result;
    }

    @Override
    public McpHubVO getHub(Long hubId) {
        CapabilityMcpHub hub = requireVisibleHub(hubId);
        return enrich(List.of(hub)).get(0);
    }

    @Override
    public List<McpHubVO> listFollowed() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return List.of();
        }
        List<Long> hubIds = followMapper.selectList(Wrappers.<CapabilityMcpFollow>lambdaQuery()
                        .eq(CapabilityMcpFollow::getUserId, userId))
                .stream().map(CapabilityMcpFollow::getHubMcpId).distinct().toList();
        if (hubIds.isEmpty()) {
            return List.of();
        }
        List<CapabilityMcpHub> hubs = hubMapper.selectBatchIds(hubIds).stream()
                .filter(h -> STATUS_ACTIVE.equals(h.getStatus()))
                .toList();
        return enrich(hubs);
    }

    // ==================== follow / install ====================

    @Override
    @Transactional
    public void follow(Long hubId) {
        CapabilityMcpHub hub = requireVisibleHub(hubId);
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        CapabilityMcpFollow existing = followMapper.selectOne(
                Wrappers.<CapabilityMcpFollow>lambdaQuery()
                        .eq(CapabilityMcpFollow::getUserId, userId)
                        .eq(CapabilityMcpFollow::getHubMcpId, hubId)
                        .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        CapabilityMcpFollow follow = new CapabilityMcpFollow();
        follow.setTenantId(currentTenantId());
        follow.setUserId(userId);
        follow.setHubMcpId(hubId);
        follow.setCreatedAt(LocalDateTime.now());
        followMapper.insert(follow);
        hubMapper.update(null, Wrappers.<CapabilityMcpHub>lambdaUpdate()
                .eq(CapabilityMcpHub::getId, hub.getId())
                .setSql("follow_count = COALESCE(follow_count, 0) + 1"));
    }

    @Override
    @Transactional
    public void unfollow(Long hubId) {
        CapabilityMcpHub hub = requireVisibleHub(hubId);
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            return;
        }
        int removed = followMapper.delete(Wrappers.<CapabilityMcpFollow>lambdaQuery()
                .eq(CapabilityMcpFollow::getUserId, userId)
                .eq(CapabilityMcpFollow::getHubMcpId, hubId));
        if (removed > 0) {
            hubMapper.update(null, Wrappers.<CapabilityMcpHub>lambdaUpdate()
                    .eq(CapabilityMcpHub::getId, hub.getId())
                    .setSql("follow_count = GREATEST(COALESCE(follow_count, 0) - 1, 0)"));
        }
    }

    @Override
    @Transactional
    public McpVO install(Long hubId, Boolean autoUpdate) {
        CapabilityMcpHub hub = requireVisibleHub(hubId);
        if (isPublisher(hub)) {
            throw new BizException(CommonErrorCode.FORBIDDEN,
                    "Publisher cannot install their own hub MCP");
        }
        String operator = AuthContext.getUsername();
        CapabilityMcp copy = new CapabilityMcp();
        copy.setName(uniqueCopyName(hub.getName(), operator));
        copy.setDescription(hub.getDescription());
        copy.setServerUrl(hub.getServerUrl());
        copy.setServerType(hub.getServerType());
        copy.setAuthConfig(hub.getAuthConfig());
        copy.setToolDefinitions(hub.getToolDefinitions());
        copy.setStatus(McpCapabilityEnum.STATUS_ENABLED);
        copy.setCompressEnabled(Boolean.TRUE.equals(hub.getCompressEnabled()));
        copy.setAccessMode(hub.getAccessMode());
        copy.setSecurityLevel(hub.getSecurityLevel());
        copy.setApprovalScope(hub.getApprovalScope());
        copy.setHubMcpId(hub.getId());
        copy.setAutoUpdate(Boolean.TRUE.equals(autoUpdate));
        copy.setOriginVersion(versionOf(hub.getVersion()));
        copy.setSyncedAt(LocalDateTime.now());
        copy.setCreatedBy(operator);
        copy.setUpdatedBy(operator);
        mcpMapper.insert(copy);

        hubMapper.update(null, Wrappers.<CapabilityMcpHub>lambdaUpdate()
                .eq(CapabilityMcpHub::getId, hub.getId())
                .setSql("install_count = COALESCE(install_count, 0) + 1"));
        return converter.toVO(copy);
    }

    // ==================== runtime SPI ====================

    @Override
    public List<McpVO> listHubMcpsByIds(List<Long> hubIds) {
        if (hubIds == null || hubIds.isEmpty()) {
            return List.of();
        }
        List<Long> visible = runtimeVisibleTenants();
        return hubMapper.selectBatchIds(hubIds).stream()
                .filter(h -> STATUS_ACTIVE.equals(h.getStatus()))
                .filter(h -> visible == null || visible.contains(h.getTenantId()))
                .map(this::toMcpVO)
                .toList();
    }

    @Override
    public McpVO getHubMcp(Long hubId) {
        CapabilityMcpHub hub = hubId == null ? null : hubMapper.selectById(hubId);
        if (hub == null || !STATUS_ACTIVE.equals(hub.getStatus())) {
            return null;
        }
        List<Long> visible = runtimeVisibleTenants();
        if (visible != null && !visible.contains(hub.getTenantId())) {
            return null;
        }
        return toMcpVO(hub);
    }

    @Override
    public List<McpToolInfoVO> listHubTools(Long hubId) {
        McpVO hub = getHubMcp(hubId);
        if (hub == null) {
            return List.of();
        }
        try {
            // Negative cache key: hub ids and local MCP ids are independent sequences.
            McpTransportClient client = mcpTransportFactory.getOrCreateClient(
                    -hub.getId(), hub.getServerUrl(), hub.getServerType(), hub.getAuthConfig());
            return client.listTools();
        } catch (Exception e) {
            log.warn("Failed to list tools for hub MCP id={}: {}", hubId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public McpVO findHubByServerUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return null;
        }
        CapabilityMcpHub hub = hubMapper.selectOne(Wrappers.<CapabilityMcpHub>lambdaQuery()
                .in(CapabilityMcpHub::getTenantId, hubTenantIds())
                .eq(CapabilityMcpHub::getStatus, STATUS_ACTIVE)
                .eq(CapabilityMcpHub::getServerUrl, serverUrl)
                .orderByDesc(CapabilityMcpHub::getUpdatedAt)
                .last("LIMIT 1"));
        return hub == null ? null : toMcpVO(hub);
    }

    // ==================== helpers ====================

    private CapabilityMcpHub findBySource(Long tenantId, Long mcpId) {
        if (tenantId == null || mcpId == null) {
            return null;
        }
        return hubMapper.selectOne(Wrappers.<CapabilityMcpHub>lambdaQuery()
                .eq(CapabilityMcpHub::getTenantId, tenantId)
                .eq(CapabilityMcpHub::getSourceMcpId, mcpId)
                .last("LIMIT 1"));
    }

    private CapabilityMcpHub requireVisibleHub(Long hubId) {
        if (hubId == null) {
            throw new BizException(CapabilityMcpErrorCode.MCP_NOT_FOUND);
        }
        CapabilityMcpHub hub = hubMapper.selectById(hubId);
        if (hub == null || !STATUS_ACTIVE.equals(hub.getStatus())) {
            throw new BizException(CapabilityMcpErrorCode.MCP_NOT_FOUND);
        }
        if (!AuthContext.isSuperAdmin() && !hubTenantIds().contains(hub.getTenantId())) {
            throw new BizException(CapabilityMcpErrorCode.MCP_NOT_FOUND);
        }
        return hub;
    }

    private void requireOwnership(CapabilityMcp mcp) {
        if (AuthContext.isSuperAdmin()) {
            return;
        }
        if (!Objects.equals(AuthContext.getUsername(), mcp.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN,
                    "Not allowed to publish another user's MCP");
        }
    }

    private boolean isPublisher(CapabilityMcpHub hub) {
        Long userId = AuthContext.getUserId();
        if (hub.getPublisherUserId() != null && userId != null) {
            return Objects.equals(userId, hub.getPublisherUserId());
        }
        String username = AuthContext.getUsername();
        CapabilityMcp source = hub.getSourceMcpId() == null ? null : mcpMapper.selectById(hub.getSourceMcpId());
        return source != null
                ? Objects.equals(username, source.getCreatedBy())
                : Objects.equals(username, hub.getCreatedBy());
    }

    private List<McpHubVO> enrich(List<CapabilityMcpHub> hubs) {
        if (hubs == null || hubs.isEmpty()) {
            return List.of();
        }
        String username = AuthContext.getUsername();
        Long userId = AuthContext.getUserId();
        Set<Long> hubIds = hubs.stream().map(CapabilityMcpHub::getId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Long> followed = Collections.emptySet();
        if (userId != null && !hubIds.isEmpty()) {
            followed = followMapper.selectList(Wrappers.<CapabilityMcpFollow>lambdaQuery()
                            .eq(CapabilityMcpFollow::getUserId, userId)
                            .in(CapabilityMcpFollow::getHubMcpId, hubIds))
                    .stream().map(CapabilityMcpFollow::getHubMcpId).collect(Collectors.toSet());
        }

        Map<Long, CapabilityMcp> installed = Collections.emptyMap();
        if (username != null && !hubIds.isEmpty()) {
            installed = mcpMapper.selectList(Wrappers.<CapabilityMcp>lambdaQuery()
                            .eq(CapabilityMcp::getCreatedBy, username)
                            .in(CapabilityMcp::getHubMcpId, hubIds))
                    .stream()
                    .filter(m -> m.getHubMcpId() != null)
                    .collect(Collectors.toMap(CapabilityMcp::getHubMcpId, Function.identity(), (a, b) -> a));
        }

        List<Long> sourceIds = hubs.stream().map(CapabilityMcpHub::getSourceMcpId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, CapabilityMcp> sources = sourceIds.isEmpty() ? Collections.emptyMap()
                : mcpMapper.selectBatchIds(sourceIds).stream()
                        .collect(Collectors.toMap(CapabilityMcp::getId, Function.identity(), (a, b) -> a));

        List<McpHubVO> result = new ArrayList<>(hubs.size());
        for (CapabilityMcpHub hub : hubs) {
            McpHubVO vo = toVO(hub);
            CapabilityMcp source = hub.getSourceMcpId() == null ? null : sources.get(hub.getSourceMcpId());
            CapabilityMcp copy = installed.get(hub.getId());
            boolean mine = (hub.getPublisherUserId() != null
                    && Objects.equals(userId, hub.getPublisherUserId()))
                    || (source != null
                        ? Objects.equals(username, source.getCreatedBy())
                        : Objects.equals(username, hub.getCreatedBy()));
            vo.setMine(mine);
            vo.setFollowed(followed.contains(hub.getId()));
            vo.setInstalledMcpId(copy == null ? null : copy.getId());
            result.add(vo);
        }
        return result;
    }

    private McpHubVO toVO(CapabilityMcpHub hub) {
        McpHubVO vo = new McpHubVO();
        vo.setId(hub.getId());
        vo.setTenantId(hub.getTenantId());
        vo.setSourceMcpId(hub.getSourceMcpId());
        vo.setPublisherUserId(hub.getPublisherUserId());
        vo.setPublisherName(hub.getCreatedBy());
        vo.setName(hub.getName());
        vo.setDescription(hub.getDescription());
        vo.setServerUrl(hub.getServerUrl());
        vo.setServerType(hub.getServerType());
        vo.setAuthConfig(hub.getAuthConfig());
        vo.setToolDefinitions(hub.getToolDefinitions());
        vo.setCompressEnabled(Boolean.TRUE.equals(hub.getCompressEnabled()));
        vo.setAccessMode(hub.getAccessMode());
        vo.setSecurityLevel(hub.getSecurityLevel());
        vo.setApprovalScope(hub.getApprovalScope());
        vo.setVersion(hub.getVersion());
        vo.setSourceVersion(hub.getSourceVersion());
        vo.setInstallCount(hub.getInstallCount());
        vo.setFollowCount(hub.getFollowCount());
        vo.setCreatedAt(hub.getCreatedAt() == null ? null : hub.getCreatedAt().format(DTF));
        vo.setUpdatedAt(hub.getUpdatedAt() == null ? null : hub.getUpdatedAt().format(DTF));
        return vo;
    }

    private McpVO toMcpVO(CapabilityMcpHub hub) {
        McpVO vo = new McpVO();
        vo.setId(hub.getId());
        vo.setName(hub.getName());
        vo.setDescription(hub.getDescription());
        vo.setServerUrl(hub.getServerUrl());
        vo.setServerType(hub.getServerType());
        vo.setAuthConfig(hub.getAuthConfig());
        vo.setToolDefinitions(hub.getToolDefinitions());
        vo.setStatus(McpCapabilityEnum.STATUS_ENABLED);
        vo.setCompressEnabled(Boolean.TRUE.equals(hub.getCompressEnabled()));
        vo.setAccessMode(hub.getAccessMode());
        vo.setSecurityLevel(hub.getSecurityLevel());
        vo.setApprovalScope(hub.getApprovalScope());
        vo.setHubMcpId(hub.getId());
        vo.setHubVersion(hub.getVersion());
        vo.setOriginVersion(hub.getVersion());
        return vo;
    }

    private String uniqueCopyName(String base, String operator) {
        String name = base == null || base.isBlank() ? "MCP" : base;
        String candidate = name.length() > 56 ? name.substring(0, 56) : name;
        if (countByName(candidate, operator) == 0) {
            return candidate;
        }
        for (int i = 2; i < 100; i++) {
            String suffix = " (" + i + ")";
            String next = (candidate.length() + suffix.length() > 64
                    ? candidate.substring(0, 64 - suffix.length()) : candidate) + suffix;
            if (countByName(next, operator) == 0) {
                return next;
            }
        }
        return candidate + "-" + System.currentTimeMillis();
    }

    private long countByName(String name, String operator) {
        return mcpMapper.selectCount(Wrappers.<CapabilityMcp>lambdaQuery()
                .eq(CapabilityMcp::getName, name)
                .eq(CapabilityMcp::getCreatedBy, operator));
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId != null) {
            return tenantId;
        }
        TenantScope scope = TenantScopeHolder.currentOrFallback();
        return scope == null ? null : scope.currentTenantId();
    }

    private List<Long> hubTenantIds() {
        Long current = currentTenantId();
        if (current == null) {
            return List.of(-1L);
        }
        List<Long> ids = tenantScopeSpi.ancestorTenantIds(current);
        return ids == null || ids.isEmpty() ? List.of(current) : ids;
    }

    /** Runtime visibility: current tenant plus ancestors; null means no filtering. */
    private List<Long> runtimeVisibleTenants() {
        Long current = currentTenantId();
        return current == null ? null : hubTenantIds();
    }

    private static int versionOf(Integer version) {
        return version == null ? 0 : version;
    }

    private static int next(Integer version) {
        return versionOf(version) + 1;
    }
}

package com.buukle.agent.model.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.model.domain.AgentApiKey;
import com.buukle.agent.model.domain.AgentModelProvider;
import com.buukle.agent.model.domain.AgentModelRoute;
import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.enums.ModelProviderEnum;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.exception.ModelErrorCode;
import com.buukle.agent.model.repository.ApiKeyMapper;
import com.buukle.agent.model.repository.ModelProviderMapper;
import com.buukle.agent.model.repository.RouteMapper;
import com.buukle.agent.model.service.RouteService;
import com.buukle.agent.model.service.converter.RouteConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RouteServiceImpl extends ServiceImpl<RouteMapper, AgentModelRoute> implements RouteService {
    private final RouteConverter routeConverter;
    private final ModelProviderMapper modelProviderMapper;
    private final ApiKeyMapper apiKeyMapper;

    @Override
    @Transactional
    public ModelRouteVO createRoute(CreateRouteDTO dto) {
        assertProviderSelectable(dto.getProviderId());
        validateFallbacks(null, dto);
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            baseMapper.clearDefault(currentOwner());
        }
        AgentModelRoute route = routeConverter.toDO(dto);
        if (route.getIsDefault() == null) {
            route.setIsDefault(false);
        }
        save(route);
        ModelRouteVO vo = routeConverter.toVO(route);
        enrichFallbackNames(vo);
        enrichApiKeyConfigured(vo);
        return vo;
    }

    @Override
    public ModelRouteVO getRoute(Long id) {
        AgentModelRoute route = getById(id);
        if (route == null || !providerSelectable(route.getProviderId())) {
            throw new BizException(ModelErrorCode.ROUTE_NOT_FOUND);
        }
        ModelRouteVO vo = routeConverter.toVO(route);
        enrichFallbackNames(vo);
        enrichApiKeyConfigured(vo);
        enrichProviderName(vo);
        return vo;
    }

    @Override
    @Transactional
    public ModelRouteVO setDefault(Long id) {
        AgentModelRoute route = getById(id);
        if (route == null || !providerSelectable(route.getProviderId())) {
            throw new BizException(ModelErrorCode.ROUTE_NOT_FOUND);
        }
        baseMapper.clearDefault(currentOwner());
        lambdaUpdate()
                .eq(AgentModelRoute::getId, id)
                .set(AgentModelRoute::getIsDefault, true)
                .update();
        return getRoute(id);
    }

    @Override
    public ModelRouteVO getDefaultRoute() {
        AgentModelRoute route = baseMapper.selectDefault(currentOwner());
        if (route == null || !providerSelectable(route.getProviderId())) {
            return null;
        }
        ModelRouteVO vo = routeConverter.toVO(route);
        enrichFallbackNames(vo);
        enrichApiKeyConfigured(vo);
        enrichProviderName(vo);
        return vo;
    }

    @Override
    @Transactional
    public ModelRouteVO updateRoute(Long id, CreateRouteDTO dto) {
        assertProviderSelectable(dto.getProviderId());
        validateFallbacks(id, dto);
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            baseMapper.clearDefault(currentOwner());
        }
        AgentModelRoute route = routeConverter.toDO(dto);
        route.setId(id);
        updateById(route);
        // Reasoning-strength overrides are three-state: null = inherit from provider/global. updateById ignores
        // null, so explicitly overwrite these four fields from the whole DTO to restore inheritance for the route.
        lambdaUpdate()
                .eq(AgentModelRoute::getId, id)
                .set(AgentModelRoute::getReasoningSupported, dto.getReasoningSupported())
                .set(AgentModelRoute::getReasoningMode, dto.getReasoningMode())
                .set(AgentModelRoute::getReasoningLevels, dto.getReasoningLevels())
                .set(AgentModelRoute::getReasoningAnswerReserveRatio, dto.getReasoningAnswerReserveRatio())
                .update();
        ModelRouteVO vo = routeConverter.toVO(route);
        enrichFallbackNames(vo);
        enrichApiKeyConfigured(vo);
        enrichProviderName(vo);
        return vo;
    }

    @Override
    public void deleteRoute(Long id) {
        removeById(id);
    }

    @Override
    public List<ModelRouteVO> listRoutesByProvider(Long providerId, String keyword) {
        if (!providerExists(providerId)) {
            return List.of();
        }
        List<AgentModelRoute> routes = lambdaQuery()
                .eq(AgentModelRoute::getProviderId, providerId)
                .like(keyword != null && !keyword.isBlank(), AgentModelRoute::getModelName, keyword)
                .orderByDesc(AgentModelRoute::getCreatedAt)
                .list();
        List<ModelRouteVO> vos = routes.stream().map(routeConverter::toVO).toList();
        enrichFallbackNames(vos);
        enrichApiKeyConfigured(vos);
        enrichProviderName(vos);
        return vos;
    }

    @Override
    public List<ModelRouteVO> listAllRoutes(String keyword) {
        List<AgentModelRoute> routes = lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), AgentModelRoute::getModelName, keyword)
                .orderByDesc(AgentModelRoute::getCreatedAt)
                .list();
        // Routes whose provider was deleted (or disabled) must not be offered by any model
        // selector. Provider lookup applies logic-delete and the current tenant/owner scope.
        Set<Long> selectableProviders = selectableProviderIds(routes.stream()
                .map(AgentModelRoute::getProviderId)
                .toList());
        List<ModelRouteVO> vos = routes.stream()
                .filter(route -> selectableProviders.contains(route.getProviderId()))
                .map(routeConverter::toVO)
                .toList();
        enrichFallbackNames(vos);
        enrichApiKeyConfigured(vos);
        enrichProviderName(vos);
        return vos;
    }

    @Override
    public ModelRouteVO findSystemRoute(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        Long previousTenant = TenantContext.get();
        TenantScope previousScope = TenantScopeHolder.get();
        try {
            TenantContext.set(tenantId);
            TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, "system"));
            AgentModelRoute route = lambdaQuery()
                    .eq(AgentModelRoute::getSystemManaged, true)
                    .orderByAsc(AgentModelRoute::getId)
                    .last("LIMIT 1")
                    .one();
            return route == null ? null : routeConverter.toVO(route);
        } finally {
            TenantScopeHolder.clear();
            TenantContext.clear();
            if (previousScope != null) {
                TenantScopeHolder.start(previousScope);
            }
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            }
        }
    }

    @Override
    @Transactional
    public void markSystemRoute(Long id) {
        if (id == null) {
            return;
        }
        lambdaUpdate()
                .eq(AgentModelRoute::getId, id)
                .set(AgentModelRoute::getSystemManaged, true)
                .update();
    }

    @Override
    public List<ModelRouteVO> listSystemRoutes() {
        // Audit view: system routes belong to the tenant, so the owner-created_by
        // restriction (self-only scale) must not hide routes created by another user.
        TenantScope scope = TenantScopeHolder.currentOrFallback();
        TenantScope previous = TenantScopeHolder.get();
        try {
            if (scope != null && scope.selfOnly()) {
                TenantScopeHolder.start(new TenantScope(
                        scope.userId(), scope.username(), scope.currentTenantId(),
                        scope.visibleTenantIds(), false,
                        scope.manageableTenantIds(), scope.subtreeVisible()));
            }
            List<AgentModelRoute> routes = lambdaQuery()
                    .eq(AgentModelRoute::getSystemManaged, true)
                    .orderByAsc(AgentModelRoute::getTenantId)
                    .orderByAsc(AgentModelRoute::getId)
                    .list();
            List<ModelRouteVO> vos = routes.stream().map(routeConverter::toVO).toList();
            enrichFallbackNames(vos);
            enrichApiKeyConfigured(vos);
            enrichProviderName(vos);
            return vos;
        } finally {
            if (previous == null) {
                TenantScopeHolder.clear();
            } else {
                TenantScopeHolder.start(previous);
            }
        }
    }

    private static String currentOwner() {
        String tenant = TenantUtil.get();
        if (tenant != null && !tenant.isBlank()) {
            return tenant;
        }
        String username = AuthContext.getUsername();
        if (username != null && !username.isBlank()) {
            return username;
        }
        return "system";
    }

    /** A route can only be selected/used while its provider still exists and is active. */
    private boolean providerSelectable(Long providerId) {
        if (providerId == null) {
            return false;
        }
        AgentModelProvider provider = modelProviderMapper.selectById(providerId);
        return provider != null && ModelProviderEnum.STATUS_ACTIVE.equals(provider.getStatus());
    }

    /** Management list: a disabled provider's routes remain viewable, but a deleted provider's do not. */
    private boolean providerExists(Long providerId) {
        return providerId != null && modelProviderMapper.selectById(providerId) != null;
    }

    /** Batch version of {@link #providerSelectable(Long)} for list endpoints. */
    private Set<Long> selectableProviderIds(Collection<Long> providerIds) {
        Set<Long> ids = providerIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Set.of();
        }
        return modelProviderMapper.selectBatchIds(ids).stream()
                .filter(provider -> ModelProviderEnum.STATUS_ACTIVE.equals(provider.getStatus()))
                .map(AgentModelProvider::getId)
                .collect(Collectors.toSet());
    }

    private void assertProviderSelectable(Long providerId) {
        if (!providerSelectable(providerId)) {
            throw new BizException(ModelErrorCode.PROVIDER_NOT_FOUND);
        }
    }

    private void enrichApiKeyConfigured(ModelRouteVO vo) {
        if (vo == null || vo.getProviderId() == null) return;
        AgentModelProvider provider = modelProviderMapper.selectById(vo.getProviderId());
        vo.setApiKeyConfigured(provider != null && provider.getApiKeyId() != null
                && apiKeyExists(provider.getApiKeyId()));
    }

    private boolean apiKeyExists(Long apiKeyId) {
        if (apiKeyId == null) return false;
        return apiKeyMapper.selectById(apiKeyId) != null;
    }

    private void enrichApiKeyConfigured(List<ModelRouteVO> vos) {
        if (vos == null || vos.isEmpty()) return;
        Set<Long> providerIds = vos.stream()
                .map(ModelRouteVO::getProviderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (providerIds.isEmpty()) return;
        List<AgentModelProvider> providers = modelProviderMapper.selectBatchIds(providerIds);
        Map<Long, Long> providerKeyMap = providers.stream()
                .filter(p -> p.getApiKeyId() != null)
                .collect(Collectors.toMap(AgentModelProvider::getId, AgentModelProvider::getApiKeyId));
        Set<Long> validKeyIds = providerKeyMap.values().isEmpty() ? Collections.emptySet()
                : apiKeyMapper.selectBatchIds(providerKeyMap.values())
                .stream().map(AgentApiKey::getId)
                .collect(Collectors.toSet());
        for (ModelRouteVO vo : vos) {
            if (vo.getProviderId() != null) {
                Long apiKeyId = providerKeyMap.get(vo.getProviderId());
                vo.setApiKeyConfigured(apiKeyId != null && validKeyIds.contains(apiKeyId));
            }
        }
    }

    private void enrichProviderName(ModelRouteVO vo) {
        if (vo == null || vo.getProviderId() == null) return;
        AgentModelProvider provider = modelProviderMapper.selectById(vo.getProviderId());
        if (provider != null) vo.setProviderName(provider.getName());
    }

    private void enrichProviderName(List<ModelRouteVO> vos) {
        if (vos == null || vos.isEmpty()) return;
        Set<Long> providerIds = vos.stream()
                .map(ModelRouteVO::getProviderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (providerIds.isEmpty()) return;
        Map<Long, String> nameMap = modelProviderMapper.selectBatchIds(providerIds)
                .stream().collect(Collectors.toMap(AgentModelProvider::getId, AgentModelProvider::getName));
        for (ModelRouteVO vo : vos) {
            if (vo.getProviderId() != null)
                vo.setProviderName(nameMap.get(vo.getProviderId()));
        }
    }

    private void validateFallbacks(Long routeId, CreateRouteDTO dto) {
        String fallbackIds = dto.getFallbackIds();
        if (fallbackIds == null || fallbackIds.isBlank()) return;

        List<Long> fbIdList = Arrays.stream(fallbackIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();

        if (fbIdList.isEmpty()) return;

        List<AgentModelRoute> allRoutes = lambdaQuery()
                .eq(AgentModelRoute::getProviderId, dto.getProviderId())
                .list();
        Map<Long, AgentModelRoute> routeMap = allRoutes.stream()
                .collect(Collectors.toMap(AgentModelRoute::getId, r -> r));

        for (Long fbId : fbIdList) {
            if (!routeMap.containsKey(fbId)) {
                throw new BizException(ModelErrorCode.ROUTE_FALLBACK_NOT_FOUND);
            }
        }

        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new LinkedList<>(fbIdList);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (current.equals(routeId)) {
                throw new BizException(ModelErrorCode.ROUTE_FALLBACK_CYCLE);
            }
            if (!visited.add(current)) continue;

            AgentModelRoute currentRoute = routeMap.get(current);
            if (currentRoute == null) continue;

            String fb = RouteConverter.fromJsonArray(currentRoute.getFallbackIds());
            if (fb != null && !fb.isBlank()) {
                Arrays.stream(fb.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::valueOf)
                        .forEach(queue::add);
            }
        }
    }

    private void enrichFallbackNames(ModelRouteVO vo) {
        if (vo.getFallbackIds() == null || vo.getFallbackIds().isBlank()) {
            vo.setFallbackNames("");
            return;
        }
        List<Long> ids = Arrays.stream(vo.getFallbackIds().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
        if (ids.isEmpty()) {
            vo.setFallbackNames("");
            return;
        }
        List<AgentModelRoute> fallbackRoutes = lambdaQuery()
                .in(AgentModelRoute::getId, ids)
                .list();
        Map<Long, String> nameMap = fallbackRoutes.stream()
                .collect(Collectors.toMap(AgentModelRoute::getId, AgentModelRoute::getModelName));
        String names = ids.stream()
                .map(id -> {
                    String name = nameMap.getOrDefault(id, "?");
                    return name + "(ID:" + id + ")";
                })
                .collect(Collectors.joining(", "));
        vo.setFallbackNames(names);
    }

    private void enrichFallbackNames(List<ModelRouteVO> vos) {
        Set<Long> allIds = vos.stream()
                .filter(vo -> vo.getFallbackIds() != null && !vo.getFallbackIds().isBlank())
                .flatMap(vo -> Arrays.stream(vo.getFallbackIds().split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        if (allIds.isEmpty()) {
            vos.forEach(vo -> vo.setFallbackNames(""));
            return;
        }
        List<AgentModelRoute> fallbackRoutes = lambdaQuery()
                .in(AgentModelRoute::getId, allIds)
                .list();
        Map<Long, String> nameMap = fallbackRoutes.stream()
                .collect(Collectors.toMap(AgentModelRoute::getId, AgentModelRoute::getModelName));
        for (ModelRouteVO vo : vos) {
            if (vo.getFallbackIds() == null || vo.getFallbackIds().isBlank()) {
                vo.setFallbackNames("");
                continue;
            }
            String names = Arrays.stream(vo.getFallbackIds().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .map(id -> {
                        String name = nameMap.getOrDefault(id, "?");
                        return name + "(ID:" + id + ")";
                    })
                    .collect(Collectors.joining(", "));
            vo.setFallbackNames(names);
        }
    }
}

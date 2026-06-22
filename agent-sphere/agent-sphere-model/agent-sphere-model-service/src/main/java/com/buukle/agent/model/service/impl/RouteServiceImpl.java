package com.buukle.agent.model.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.model.domain.AgentModelProvider;
import com.buukle.agent.model.domain.AgentModelRoute;
import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.exception.ModelErrorCode;
import com.buukle.agent.model.repository.ModelProviderMapper;
import com.buukle.agent.model.repository.RouteMapper;
import com.buukle.agent.model.service.RouteService;
import com.buukle.agent.model.service.converter.RouteConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RouteServiceImpl extends ServiceImpl<RouteMapper, AgentModelRoute> implements RouteService {
    private final RouteConverter routeConverter;
    private final ModelProviderMapper modelProviderMapper;

    @Override
    public ModelRouteVO createRoute(CreateRouteDTO dto) {
        validateFallbacks(null, dto);
        AgentModelRoute route = routeConverter.toDO(dto);
        save(route);
        ModelRouteVO vo = routeConverter.toVO(route);
        enrichFallbackNames(vo);
        enrichApiKeyConfigured(vo);
        return vo;
    }

    @Override
    public ModelRouteVO getRoute(Long id) {
        AgentModelRoute route = getById(id);
        if (route == null) throw new BizException(ModelErrorCode.ROUTE_NOT_FOUND);
        ModelRouteVO vo = routeConverter.toVO(route);
        enrichFallbackNames(vo);
        enrichApiKeyConfigured(vo);
        enrichProviderName(vo);
        return vo;
    }

    @Override
    public ModelRouteVO updateRoute(Long id, CreateRouteDTO dto) {
        validateFallbacks(id, dto);
        AgentModelRoute route = routeConverter.toDO(dto);
        route.setId(id);
        updateById(route);
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
        List<ModelRouteVO> vos = routes.stream().map(routeConverter::toVO).toList();
        enrichFallbackNames(vos);
        enrichApiKeyConfigured(vos);
        enrichProviderName(vos);
        return vos;
    }

    private void enrichApiKeyConfigured(ModelRouteVO vo) {
        if (vo == null || vo.getProviderId() == null) return;
        AgentModelProvider provider = modelProviderMapper.selectById(vo.getProviderId());
        vo.setApiKeyConfigured(provider != null && provider.getApiKeyId() != null);
    }

    private void enrichApiKeyConfigured(List<ModelRouteVO> vos) {
        if (vos == null || vos.isEmpty()) return;
        Set<Long> providerIds = vos.stream()
                .map(ModelRouteVO::getProviderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (providerIds.isEmpty()) return;
        Map<Long, Boolean> keyMap = modelProviderMapper.selectBatchIds(providerIds)
                .stream().collect(Collectors.toMap(AgentModelProvider::getId, p -> p.getApiKeyId() != null));
        for (ModelRouteVO vo : vos) {
            if (vo.getProviderId() != null)
                vo.setApiKeyConfigured(keyMap.getOrDefault(vo.getProviderId(), false));
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

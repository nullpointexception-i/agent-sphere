package com.buukle.agent.model.service.converter;

import com.buukle.agent.model.domain.AgentModelRoute;
import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.dtvo.enums.RouteEnum;
import com.buukle.agent.model.dtvo.constants.RouteConstants;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class RouteConverter {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String toJsonArray(String csv) {
        if (csv == null || csv.isBlank()) return "[]";
        if (csv.trim().startsWith("[")) return csv; // already JSON
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    }

    public static String fromJsonArray(String json) {
        if (json == null || json.isBlank()) return "";
        String cleaned = json.replaceAll("[{}]", "").replaceAll("^,+", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("[]")) return "";
        if (cleaned.startsWith("[")) {
            return cleaned.replaceAll("[\\[\\]\"]", "");
        }
        return cleaned;
    }

    public ModelRouteVO toVO(AgentModelRoute route) {
        if (route == null) return null;
        ModelRouteVO vo = new ModelRouteVO();
        vo.setId(route.getId());
        vo.setProviderId(route.getProviderId());
        vo.setModelName(route.getModelName());
        vo.setWeight(route.getWeight());
        vo.setFallbackIds(fromJsonArray(route.getFallbackIds()));
        vo.setMaxInputTokens(route.getMaxInputTokens());
        vo.setMaxOutputTokens(route.getMaxOutputTokens());
        vo.setStatus(route.getStatus());
        vo.setCompany(route.getCompany());
        vo.setCreatedAt(route.getCreatedAt() != null ? route.getCreatedAt().format(DTF) : null);
        vo.setCreatedBy(route.getCreatedBy());
        vo.setUpdatedBy(route.getUpdatedBy());
        vo.setUpdatedAt(route.getUpdatedAt() != null ? route.getUpdatedAt().format(DTF) : null);
        return vo;
    }

    public AgentModelRoute toDO(CreateRouteDTO dto) {
        AgentModelRoute route = new AgentModelRoute();
        route.setProviderId(dto.getProviderId());
        route.setModelName(dto.getModelName());
        route.setWeight(dto.getWeight() != null ? dto.getWeight() : RouteConstants.DEFAULT_WEIGHT);
        route.setFallbackIds(toJsonArray(dto.getFallbackIds()));
        route.setMaxInputTokens(dto.getMaxInputTokens());
        route.setMaxOutputTokens(dto.getMaxOutputTokens());
        route.setStatus(RouteEnum.STATUS_ACTIVE);
        route.setCompany(dto.getCompany());
        return route;
    }
}

package com.buukle.agent.resource.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.buukle.agent.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资源模板协调器：解析模板 JSON 数组，按 type 分发给对应 {@link ResourceInitializer}。
 * 依赖 Spring 自动收集所有 ResourceInitializer bean，新增资源类型零改动。
 */
@Slf4j
@Component
public class ResourceTemplateCoordinator {

    private final Map<String, ResourceInitializer> byType;

    public ResourceTemplateCoordinator(List<ResourceInitializer> initializers) {
        this.byType = initializers.stream().collect(Collectors.toMap(ResourceInitializer::type, Function.identity()));
    }

    /**
     * 执行资源模板初始化。
     *
     * @param templateJson 资源描述符 JSON 数组；空则返回空结果（默认模板由调用方决定是否传入）
     * @param ctx          上下文（身份源信息 + 已建资源缓存）
     */
    public ResourceInitResult initialize(String templateJson, ResourceInitContext ctx) {
        ResourceInitResult result = new ResourceInitResult();
        if (templateJson == null || templateJson.isBlank()) {
            return result;
        }
        JsonNode arr;
        try {
            arr = JsonUtils.getMapper().readTree(templateJson);
        } catch (Exception e) {
            log.warn("Resource template parse failed: {}", e.getMessage());
            result.failed("template parse error: " + e.getMessage());
            return result;
        }
        if (arr == null || !arr.isArray()) {
            result.failed("template is not a JSON array");
            return result;
        }
        for (JsonNode item : arr) {
            if (!item.isObject()) {
                continue;
            }
            String type = item.path("type").asText("");
            ResourceInitializer initializer = byType.get(type);
            if (initializer == null) {
                result.unknown(type);
                continue;
            }
            try {
                initializer.initialize(item, ctx);
                result.created();
            } catch (ResourceExistsException e) {
                result.skipped();
            } catch (Exception e) {
                log.warn("Resource template init failed type={}: {}", type, e.getMessage());
                result.failed(type + ": " + e.getMessage());
            }
        }
        return result;
    }
}

package com.buukle.agent.resource.template;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Resource template initialization context: carries identity provider info + a cache of created
 * resources (for cross-type references, e.g. completions/instance referencing a route id).
 */
@Getter
@Setter
public class ResourceInitContext {

    private String providerCode;
    private String providerName;
    private String operator;

    private final Map<String, Long> created = new HashMap<>();

    public ResourceInitContext(String providerCode, String providerName, String operator) {
        this.providerCode = providerCode;
        this.providerName = providerName;
        this.operator = operator;
    }

    public void put(String type, String name, Long id) {
        created.put(key(type, name), id);
    }

    public Long get(String type, String name) {
        return name == null ? null : created.get(key(type, name));
    }

    private static String key(String type, String name) {
        return type + ":" + name;
    }
}

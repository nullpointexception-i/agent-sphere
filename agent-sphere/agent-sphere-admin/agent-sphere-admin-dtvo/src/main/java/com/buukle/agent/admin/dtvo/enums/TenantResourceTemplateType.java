package com.buukle.agent.admin.dtvo.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Resource kinds supported by {@code tenant.resource-template}. */
public enum TenantResourceTemplateType {

    MODEL_PROVIDER("model_provider"),
    MODEL_ROUTE("model_route"),
    INSTANCE("instance"),
    UNKNOWN("");

    private final String value;

    TenantResourceTemplateType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static TenantResourceTemplateType from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        for (TenantResourceTemplateType type : values()) {
            if (type.value.equalsIgnoreCase(raw)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}

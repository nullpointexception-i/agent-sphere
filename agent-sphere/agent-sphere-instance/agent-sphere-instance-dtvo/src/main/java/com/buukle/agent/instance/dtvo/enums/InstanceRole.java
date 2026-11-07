package com.buukle.agent.instance.dtvo.enums;

/**
 * Instance roles used by the Super-Agent runtime.
 *
 * <p>{@link #CHAT} is the default conversational instance. The remaining roles are
 * created internally by the Super-Agent lifecycle and must not be exposed as ordinary
 * instances. Values are persisted in {@code agent_instance.instance_role} and must stay
 * stable.
 */
public enum InstanceRole {

    CHAT,
    GROUP_ROUTER,
    ROUTER,
    WORKER,
    WATCHDOG;

    public String value() {
        return name();
    }

    public static InstanceRole from(String value) {
        if (value == null || value.isBlank()) {
            return CHAT;
        }
        for (InstanceRole role : values()) {
            if (role.name().equalsIgnoreCase(value)) {
                return role;
            }
        }
        return CHAT;
    }

    public static String normalize(String value) {
        return from(value).value();
    }
}

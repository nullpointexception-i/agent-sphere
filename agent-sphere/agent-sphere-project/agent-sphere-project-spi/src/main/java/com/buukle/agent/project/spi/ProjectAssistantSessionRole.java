package com.buukle.agent.project.spi;

/** Role of a project-assistant session. */
public enum ProjectAssistantSessionRole {

    CANONICAL,
    FORK;

    public String value() {
        return name();
    }

    public static ProjectAssistantSessionRole from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ProjectAssistantSessionRole role : values()) {
            if (role.name().equalsIgnoreCase(value.trim())) {
                return role;
            }
        }
        return null;
    }
}

package com.buukle.agent.project.dtvo.enums;

/** Persisted project lifecycle status. */
public enum ProjectStatus {

    ACTIVE,
    ARCHIVED;

    public String value() {
        return name();
    }

    public static ProjectStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ProjectStatus status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return null;
    }
}

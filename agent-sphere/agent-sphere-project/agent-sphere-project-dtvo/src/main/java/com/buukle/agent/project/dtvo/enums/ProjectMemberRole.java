package com.buukle.agent.project.dtvo.enums;

/** Persisted project member roles, ordered from most to least privileged. */
public enum ProjectMemberRole {

    OWNER(4),
    MANAGER(3),
    MEMBER(2),
    VIEWER(1);

    private final int rank;

    ProjectMemberRole(int rank) {
        this.rank = rank;
    }

    public String value() {
        return name();
    }

    public int rank() {
        return rank;
    }

    public static ProjectMemberRole from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ProjectMemberRole role : values()) {
            if (role.name().equalsIgnoreCase(value.trim())) {
                return role;
            }
        }
        return null;
    }

    public static int rankOf(String value) {
        ProjectMemberRole role = from(value);
        return role == null ? 0 : role.rank;
    }
}

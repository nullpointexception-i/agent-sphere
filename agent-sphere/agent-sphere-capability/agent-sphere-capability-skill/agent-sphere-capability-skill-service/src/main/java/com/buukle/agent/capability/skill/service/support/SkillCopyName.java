package com.buukle.agent.capability.skill.service.support;

import java.util.function.Predicate;

/** Shared naming rule for installed copies: append " (n)" until the name is free. */
public final class SkillCopyName {

    private static final int MAX_BASE_LENGTH = 56;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_SUFFIX = 10000;

    private SkillCopyName() {
    }

    public static String resolve(String base, Predicate<String> existsByName) {
        String name = base == null ? "" : base;
        if (name.length() > MAX_BASE_LENGTH) {
            name = name.substring(0, MAX_BASE_LENGTH);
        }
        if (!existsByName.test(name)) {
            return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
        }
        for (int n = 1; n < MAX_SUFFIX; n++) {
            String suffix = " (" + n + ")";
            String candidate = base + suffix;
            if (candidate.length() > MAX_NAME_LENGTH) {
                int keep = Math.max(0, MAX_NAME_LENGTH - suffix.length());
                candidate = base.substring(0, Math.min(keep, base.length())) + suffix;
            }
            if (!existsByName.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Too many name collisions; choose another name and install again");
    }
}

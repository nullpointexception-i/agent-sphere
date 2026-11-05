package com.buukle.agent.superagent.service.support;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Prompt fragments shared by the agent router and worker instances. */
public final class SuperAgentPrompt {

    private static final String ROLE_TEMPLATE =
            "You are a %s. Use the available tools and skills to help the user achieve their goals.";
    private static final String SCOPE_TEMPLATE = "Your job scope is %s";
    private static final String STRICT_TEMPLATE =
            "STRICT: You may only reply to requests related to %s. "
                    + "If a request is unrelated, decline it briefly.";

    /** Role identity + job scope shared by router and worker. */
    public static String roleBase(String roleName, String description) {
        String role = trimToEmpty(roleName);
        if (role.isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder(ROLE_TEMPLATE.formatted(role));
        String scope = trimToEmpty(description);
        if (!scope.isEmpty()) {
            prompt.append(' ').append(SCOPE_TEMPLATE.formatted(withEnding(scope)));
        }
        return prompt.toString();
    }

    /** Full STRICT instruction for one role. */
    public static String strict(String roleName) {
        String role = trimToEmpty(roleName);
        return role.isEmpty() ? "" : STRICT_TEMPLATE.formatted(role);
    }

    /** Worker prompt: role identity, scope and the strict reply boundary. */
    public static String workerPrompt(String roleName, String description) {
        return join(roleBase(roleName, description), strict(roleName));
    }

    /** Router prompt: role identity first, then the global routing instructions. */
    public static String routerPrompt(String roleName, String description, String routerInstructions) {
        return join(roleBase(roleName, description), routerInstructions, strict(roleName));
    }

    private static String join(String... parts) {
        return Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(Collectors.joining("\n\n"));
    }

    private static String withEnding(String text) {
        char last = text.charAt(text.length() - 1);
        return switch (last) {
            case '.', '!', '?', '。', '！', '？' -> text;
            default -> text + ".";
        };
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private SuperAgentPrompt() {
    }
}

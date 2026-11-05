package com.buukle.agent.superagent.service.support;

/** Prompt fragments shared by the agent router and worker instances. */
public final class SuperAgentPrompt {

    private static final String STRICT_TEMPLATE =
            "STRICT: You may only reply to requests related to %s. "
                    + "If a request is unrelated, decline it briefly.";

    /** Full STRICT instruction for one position. */
    public static String strict(String positionName) {
        if (positionName == null || positionName.isBlank()) {
            return "";
        }
        return STRICT_TEMPLATE.formatted(positionName.trim());
    }

    /** Append the position STRICT instruction to a base prompt. */
    public static String withStrict(String basePrompt, String positionName) {
        String strict = strict(positionName);
        if (strict.isBlank()) {
            return basePrompt == null ? "" : basePrompt;
        }
        String base = basePrompt == null ? "" : basePrompt.stripTrailing();
        return base.isEmpty() ? strict : base + "\n\n" + strict;
    }

    private SuperAgentPrompt() {
    }
}

package com.buukle.agent.common.constant;

/** Run completion contract after an agent has executed tools. */
public enum CompletionProtocol {
    /** A tool-using run must call finish explicitly. */
    STRICT_FINISH,
    /** A stop response with non-empty text may complete the run for compatibility. */
    IMPLICIT_TEXT;

    /** API/UI sentinel: instance does not override the model route default. Never persisted. */
    public static final String INHERIT = "MODEL_DEFAULT";

    /**
     * Resolve the effective protocol: instance override > model route default > strict.
     * The {@link #INHERIT} sentinel and blank values mean "follow the route".
     */
    public static CompletionProtocol resolve(String instanceOverride, String routeDefault) {
        if (instanceOverride != null && !instanceOverride.isBlank()
                && !INHERIT.equalsIgnoreCase(instanceOverride)) {
            return from(instanceOverride);
        }
        return from(routeDefault);
    }

    public static boolean isInherit(String value) {
        return value == null || value.isBlank() || INHERIT.equalsIgnoreCase(value);
    }

    public static CompletionProtocol from(String value) {
        if (value == null || value.isBlank()) {
            return STRICT_FINISH;
        }
        for (CompletionProtocol protocol : values()) {
            if (protocol.name().equalsIgnoreCase(value)) {
                return protocol;
            }
        }
        return STRICT_FINISH;
    }
}

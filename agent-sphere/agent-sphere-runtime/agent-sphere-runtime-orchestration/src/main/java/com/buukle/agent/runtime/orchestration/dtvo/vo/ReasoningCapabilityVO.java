package com.buukle.agent.runtime.orchestration.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Reasoning-effort capability view for a session.
 *
 * <p>Infeasible levels are still returned (the frontend displays them with a warning) rather than hidden.
 */
@Data
public class ReasoningCapabilityVO implements Serializable {

    /** Whether the model used by the current session supports reasoning effort. */
    private boolean supported;
    /** Unified mode: levels. */
    private String mode;
    /** Output cap actually in effect for this request (max_tokens). */
    private Long effectiveMaxOutputTokens;
    /** Number of tokens reserved for answer content. */
    private Long answerReserveTokens;
    /** Dynamic default level. */
    private String defaultLevel;
    /** Currently selected level; null when no run is active (the default is used on send). */
    private String selectedLevel;
    /** Level actually used for this turn (downgraded when the manual choice is infeasible); while running, the value resolved for the current turn. */
    private String effectiveLevel;
    /** Selectable levels (including infeasible ones, marked by feasible). */
    private List<Level> levels;
    /** Whether the session has an active run; cannot be changed while running. */
    private boolean running;
    /** Active run id. */
    private Long runId;

    @Data
    public static class Level implements Serializable {
        private String value;
        /** Minimum output budget recommended for this level. */
        private long minOutputTokens;
        /** Whether this level is feasible under the current output cap. */
        private boolean feasible;
    }
}

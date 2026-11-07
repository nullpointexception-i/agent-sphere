package com.buukle.agent.capability.builtin.tool.textcontains.dtvo.enums;

/**
 * Matching mode for `text_contains`.
 *
 * <p>Deliberately an explicit enum rather than a set of boolean flags: boolean combinations of
 * "case sensitive" and "trim whitespace" produce four poorly defined switch states, whereas an
 * enum is listed directly as allowed values in the parameter schema, so the model need not infer.
 */
public enum TextMatchMode {

    /** Strict substring match (default): case-sensitive, no normalization. */
    EXACT,

    /** Case-insensitive. */
    IGNORE_CASE,

    /** Strict match after trimming both ends. */
    TRIMMED,

    /** Trim both ends plus case-insensitive. */
    IGNORE_CASE_TRIMMED
}

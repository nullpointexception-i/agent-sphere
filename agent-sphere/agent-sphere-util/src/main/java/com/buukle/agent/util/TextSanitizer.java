package com.buukle.agent.util;

/**
 * Text sanitizing utility: removes characters the database/protocol does not allow.
 *
 * PostgreSQL text/varchar columns reject the NUL byte (0x00); an occasional \u0000 in LLM output
 * makes INSERT/UPDATE throw DataIntegrityViolationException. Sanitize at the LLM text entry points
 * and persistence boundaries to keep it out of columns such as run.reasoning / reply /
 * user_message / tool artifact.
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /** Removes NUL (\u0000); all other characters are preserved. Null-safe. */
    public static String sanitize(String text) {
        if (text == null || text.indexOf('\u0000') < 0) {
            return text;
        }
        return text.replace("\u0000", "");
    }
}
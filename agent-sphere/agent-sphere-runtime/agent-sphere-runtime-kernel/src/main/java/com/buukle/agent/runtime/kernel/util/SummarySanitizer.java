package com.buukle.agent.runtime.kernel.util;

import com.buukle.agent.runtime.kernel.constants.RunnerConstants;

/**
 * Completeness check and over-budget trimming for compaction summaries.
 *
 * <p>When a summary is truncated by max_tokens, the model may stop in the middle of an unclosed code fence/JSON/quotes. This adds one layer of
 * structural fallback; the real authoritative signal is still the stream's {@code finish_reason=length}.
 */
public final class SummarySanitizer {

    private SummarySanitizer() {
    }

    public static boolean isComplete(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.stripTrailing();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (count(trimmed, "```") % 2 != 0) {
            return false;
        }
        if (count(trimmed, "{") != count(trimmed, "}")
                || count(trimmed, "[") != count(trimmed, "]")) {
            return false;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        return "{[:,\"\\-".indexOf(last) < 0;
    }

    /**
     * Trims an over-budget summary to the token budget, stopping at a complete sentence/paragraph boundary where possible.
     * Returns null when no complete boundary is found, letting the caller treat it as a failure instead of persisting half a JSON/code block.
     */
    public static String trimToTokenBudget(String text, long maxTokens) {
        if (text == null || text.isBlank() || maxTokens <= 0) {
            return text;
        }
        long maxChars = Math.max(1L, (long) Math.floor(maxTokens * RunnerConstants.TOKEN_ESTIMATE_DIVISOR));
        if (text.length() <= maxChars && isComplete(text)) {
            return text;
        }
        int cut = (int) Math.min(text.length(), maxChars);
        while (cut > 0) {
            cut = lastBoundary(text, cut);
            if (cut <= 0) {
                break;
            }
            String candidate = text.substring(0, cut).stripTrailing();
            if (!candidate.isEmpty() && isComplete(candidate)) {
                return candidate;
            }
            cut--;
        }
        String fallback = text.substring(0, (int) Math.min(text.length(), maxChars)).stripTrailing();
        return isComplete(fallback) ? fallback : null;
    }

    private static int lastBoundary(String text, int limit) {
        int from = Math.min(limit, text.length());
        for (int i = from; i > 0; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？'
                    || c == '.' || c == '!' || c == '?') {
                return i;
            }
        }
        return 0;
    }

    private static int count(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(token, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + token.length();
        }
    }
}

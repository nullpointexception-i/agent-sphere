package com.buukle.agent.runtime.kernel.util;

import com.buukle.agent.runtime.kernel.constants.RunnerConstants;

/**
 * 压缩摘要完整性检查与超预算裁剪。
 *
 * <p>摘要被 max_tokens 截断时，模型可能停在未闭合的代码围栏/JSON/引号中间。这里做一层
 * 结构兜底；真正的权威信号仍是流结束的 {@code finish_reason=length}。
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
     * 把超预算摘要裁到 token 预算内，并尽量停在完整句子/段落边界。
     * 找不到完整边界时返回 null，由调用方按失败处理，避免落库半截 JSON/代码块。
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

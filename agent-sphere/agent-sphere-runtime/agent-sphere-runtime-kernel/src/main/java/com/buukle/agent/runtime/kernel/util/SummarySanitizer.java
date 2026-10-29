package com.buukle.agent.runtime.kernel.util;

/**
 * 压缩摘要完整性检查（只判断，不修改内容）。
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

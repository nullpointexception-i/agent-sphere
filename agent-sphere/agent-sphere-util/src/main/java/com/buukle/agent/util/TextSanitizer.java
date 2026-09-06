package com.buukle.agent.util;

/**
 * 文本清洗工具：剔除数据库/协议不允许的字符。
 *
 * PostgreSQL 的 text/varchar 列不接受 NUL 字节（0x00），LLM 输出偶发携带 \u0000
 * 会导致 INSERT/UPDATE 抛 DataIntegrityViolationException。在 LLM 文本入口与落库边界
 * 统一清洗，防止污染 run.reasoning / reply / user_message / tool artifact 等列。
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /** 移除 NUL（\u0000）；其余字符原样保留。null 安全。 */
    public static String sanitize(String text) {
        if (text == null || text.indexOf('\u0000') < 0) {
            return text;
        }
        return text.replace("\u0000", "");
    }
}
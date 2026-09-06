package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.util.TextSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextSanitizerTest {

    @Test
    void sanitize_removesNulOnly() {
        assertEquals("abc", TextSanitizer.sanitize("a\u0000b\u0000c"));
        assertEquals("", TextSanitizer.sanitize("\u0000\u0000"));
    }

    @Test
    void sanitize_keepsNormalTextAndOtherControls() {
        assertEquals("流程管理", TextSanitizer.sanitize("流程管理"));
        // 其它控制符（如 \n）保留，仅 NUL 被剥
        assertEquals("a\nb\tc", TextSanitizer.sanitize("a\nb\tc"));
    }

    @Test
    void sanitize_nullSafe() {
        assertNull(TextSanitizer.sanitize(null));
    }
}

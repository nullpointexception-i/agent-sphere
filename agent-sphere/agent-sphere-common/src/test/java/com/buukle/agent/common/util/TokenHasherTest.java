package com.buukle.agent.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenHasherTest {

    @Test
    void generate_shouldReturnUrlSafeToken() {
        String token = TokenHasher.generate();
        assertEquals(43, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]+"));
        assertNotEquals(token, TokenHasher.generate());
    }

    @Test
    void sha256_shouldBeDeterministicAndHexEncoded() {
        String first = TokenHasher.sha256("raw-token");
        assertEquals(first, TokenHasher.sha256("raw-token"));
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, TokenHasher.sha256("other-token"));
    }

    @Test
    void prefix_shouldBeShortEnoughForSupportLookup() {
        assertEquals("abcdefgh", TokenHasher.prefix("abcdefghijklmnop"));
        assertEquals("short", TokenHasher.prefix("short"));
        assertFalse(TokenHasher.prefix("abcdefghijklmnop").contains("ijk"));
    }
}

package com.buukle.agent.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Login token generation and hashing.
 *
 * <p>The plaintext token is returned to the client only at issue time; the server stores only its
 * SHA-256 digest. Verification hashes the input the same way and compares, so a database leak does
 * not directly yield usable credentials. Generation and hashing must both use the implementation
 * here so the algorithm cannot drift between two places.
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private TokenHasher() {
    }

    /** 32 random bytes → base64url (no padding), 43 characters. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lowercase hex SHA-256; used for persistence and cache keys. */
    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /** Diagnostic prefix: lets support staff use the first few characters of a browser token to locate the specific session row. */
    public static String prefix(String raw) {
        return raw.length() <= 8 ? raw : raw.substring(0, 8);
    }
}

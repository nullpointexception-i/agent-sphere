package com.buukle.agent.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 登录 token 的生成与摘要。
 *
 * <p>token 明文只在签发时返回给客户端，服务端仅保存 SHA-256 摘要。校验时对入参做同样摘要再比对，
 * 因此数据库泄露不能直接得到可用凭证。生成与摘要必须成对使用这里的实现，避免两处算法漂移。
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private TokenHasher() {
    }

    /** 32 字节随机数 → base64url（无填充），43 个字符。 */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 小写 hex 的 SHA-256；用于落库与缓存 key。 */
    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /** 排查用的前缀：允许支持同学用浏览器里的 token 前几位定位到具体会话行。 */
    public static String prefix(String raw) {
        return raw.length() <= 8 ? raw : raw.substring(0, 8);
    }
}

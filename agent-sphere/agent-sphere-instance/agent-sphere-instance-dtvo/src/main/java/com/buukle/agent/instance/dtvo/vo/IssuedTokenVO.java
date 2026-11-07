package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Issuance result: the plaintext token is returned only here, once; the server stores only the digest.
 */
@Data
public class IssuedTokenVO implements Serializable {
    private String token;
    private LocalDateTime expiresAt;
}

package com.buukle.agent.superagent.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Issued runtime token; the plaintext is returned only once. */
@Data
public class IssuedLocalRuntimeTokenVO implements Serializable {
    private Long id;
    private String name;
    private String runtimeKind;
    private String token;
    private String tokenPrefix;
}

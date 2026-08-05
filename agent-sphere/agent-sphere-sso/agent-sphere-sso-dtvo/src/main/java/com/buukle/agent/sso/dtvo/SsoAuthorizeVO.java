package com.buukle.agent.sso.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SsoAuthorizeVO implements Serializable {
    private String provider;
    private String authorizeUrl;
    private String state;
}
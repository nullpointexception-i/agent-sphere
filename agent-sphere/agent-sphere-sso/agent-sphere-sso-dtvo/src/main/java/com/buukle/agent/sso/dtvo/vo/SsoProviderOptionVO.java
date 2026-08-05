package com.buukle.agent.sso.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SsoProviderOptionVO implements Serializable {
    private String code;
    private String name;
}

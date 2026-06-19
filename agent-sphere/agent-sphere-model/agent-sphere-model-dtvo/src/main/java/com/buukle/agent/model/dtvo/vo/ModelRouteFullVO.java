package com.buukle.agent.model.dtvo.vo;

import lombok.Data;
import java.io.Serializable;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ModelRouteFullVO extends ModelRouteVO implements Serializable {
    private String providerName;
    private String baseUrl;
    private Long apiKeyId;
    private String config;
}

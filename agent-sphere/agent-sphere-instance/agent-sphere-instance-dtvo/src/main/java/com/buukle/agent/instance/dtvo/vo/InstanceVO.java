package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class InstanceVO implements Serializable {
    private Long id;
    private String name;
    private String description;
    private String systemPrompt;
    private Long modelRouteId;
    private String customInstructions;
    private String image;
    private String status;
    private String businessType;

    /** 是否允许主站控制台发起/继续会话（V89）。 */
    private Boolean allowConsoleSession;

    /** 是否允许嵌入式 widget 发起/继续会话（V89）。 */
    private Boolean allowWidgetSession;
    /** 单次 run 最大循环次数（NULL=未配置走系统默认）。 */
    private Integer maxLoopCount;
    /** 采样/请求参数 JSON 配置（NULL=未配置）。 */
    private String config;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}

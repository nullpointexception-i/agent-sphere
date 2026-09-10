package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateInstanceDTO implements Serializable {
    @NotBlank(message = "name can't be blank")
    @Size(min = 1, max = 64)
    private String name;
    @Size(max = 255)
    private String description;
    @Size(max = 5000)
    private String systemPrompt;
    private Long modelRouteId;
    @Size(max = 5000)
    private String customInstructions;
    @Size(max = 3000000)
    private String image;
    @Size(max = 64)
    private String businessType;
    /** 单次 run 最大循环次数；NULL=未配置，0=清除覆盖，>0 覆盖系统默认。 */
    @Min(value = 0, message = "maxLoopCount must be >= 0 when configured")
    private Integer maxLoopCount;
    /** 采样/请求参数 JSON 配置（与 LlmSamplingConfigDTO 同形状）；NULL/空=未配置，更新时空字符串=清除。 */
    @Size(max = 8000, message = "config must be <= 8000 chars")
    private String config;
}

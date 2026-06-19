package com.buukle.agent.capability.builtin.tool.webread.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class WebReadExecuteContext extends ExecuteContext {
    @NotBlank
    @Schema(example = "https://example.com")
    private String url;
    @Positive
    @Schema(example = "30")
    private int timeoutSeconds = 30;
}

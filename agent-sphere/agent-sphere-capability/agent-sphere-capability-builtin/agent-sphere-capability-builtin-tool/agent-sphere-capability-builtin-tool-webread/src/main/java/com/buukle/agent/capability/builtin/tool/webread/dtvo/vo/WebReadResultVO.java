package com.buukle.agent.capability.builtin.tool.webread.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class WebReadResultVO extends ExecuteResult {
    @Schema(example = "200")
    private int statusCode;
    @Schema(example = "# Page Title\n\nContent in markdown...")
    private String markdown;
    @Schema(example = "Example Page")
    private String title;
    @Schema(example = "")
    private String errorMessage;
}

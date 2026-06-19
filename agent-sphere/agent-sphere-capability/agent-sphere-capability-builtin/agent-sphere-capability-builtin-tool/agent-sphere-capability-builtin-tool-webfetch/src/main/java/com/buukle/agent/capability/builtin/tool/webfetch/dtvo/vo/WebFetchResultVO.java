package com.buukle.agent.capability.builtin.tool.webfetch.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class WebFetchResultVO extends ExecuteResult {
    @Schema(example = "200")
    private int statusCode;
    @Schema(example = "<!DOCTYPE html>\n<html>\n<head>\n<title>Example</title>\n</head>\n<body>\n<p>Hello, world!</p>\n</body>\n</html>")
    private String content;
    @Schema(example = "text/html")
    private String contentType;
    @Schema(example = "# Example\n\nExtracted markdown content...")
    private String markdown;
    @Schema(example = "")
    private String errorMessage;
}

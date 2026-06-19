package com.buukle.agent.capability.builtin.tool.webread.dtvo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JinaReaderResponseDto {
    private JinaReaderDataDto data;
}

package com.buukle.agent.capability.mcp.dtvo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolInfoVO implements Serializable {
    private String name;
    private String description;
    private String inputSchema;
}

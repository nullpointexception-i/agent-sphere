package com.buukle.agent.agui.dtvo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class CopilotAgentDefinitionVO implements Serializable {
    private Long id;
    private String name;
    private String description = "Default agent";
    private List<String> capabilities = new ArrayList<>();
}
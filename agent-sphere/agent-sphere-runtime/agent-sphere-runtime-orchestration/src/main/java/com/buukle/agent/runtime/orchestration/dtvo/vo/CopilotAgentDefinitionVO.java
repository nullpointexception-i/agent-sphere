package com.buukle.agent.runtime.orchestration.dtvo.vo;

import lombok.Data;
import java.io.Serializable;

import java.util.Collections;
import java.util.List;

@Data
public class CopilotAgentDefinitionVO implements Serializable {
    private String description = "Default agent";
    private List<String> capabilities = Collections.emptyList();
}

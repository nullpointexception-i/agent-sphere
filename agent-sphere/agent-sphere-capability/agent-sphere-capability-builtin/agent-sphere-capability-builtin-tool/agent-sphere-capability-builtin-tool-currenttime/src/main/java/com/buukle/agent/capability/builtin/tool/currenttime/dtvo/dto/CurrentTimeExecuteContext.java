package com.buukle.agent.capability.builtin.tool.currenttime.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** The current-time tool takes no parameters; an empty context is kept so ToolExecutor can deserialize it. */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrentTimeExecuteContext extends ExecuteContext {
}

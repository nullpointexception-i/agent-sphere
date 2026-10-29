package com.buukle.agent.capability.builtin.tool.currenttime.dtvo.dto;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 当前时间工具无参数；保留空 context 以便 ToolExecutor 反序列化。 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrentTimeExecuteContext extends ExecuteContext {
}

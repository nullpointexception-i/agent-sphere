package com.buukle.agent.capability.builtin.tool.spi.dtvo;

import lombok.Data;

import java.io.Serializable;

@Data
public abstract class ExecuteContext implements Serializable {
    private Long sessionId;
    private Long runId;
    /** 模型侧工具调用 id（回传回调、定位工具记录用）。 */
    private String callId;
}

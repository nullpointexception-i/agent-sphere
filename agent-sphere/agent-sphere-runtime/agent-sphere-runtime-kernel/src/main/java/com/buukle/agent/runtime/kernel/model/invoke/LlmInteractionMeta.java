package com.buukle.agent.runtime.kernel.model.invoke;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@AllArgsConstructor
public class LlmInteractionMeta {
    private Long runId;
    private Long sessionId;
    private LlmInteractionType interactionType;

    public LlmInteractionMeta() {
    }
}

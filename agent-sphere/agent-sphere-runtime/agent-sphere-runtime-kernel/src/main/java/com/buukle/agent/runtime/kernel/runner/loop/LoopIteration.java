package com.buukle.agent.runtime.kernel.runner.loop;

import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;

import java.util.List;

/**
 * One loop iteration's resolved inputs; {@code stop} means the loop must exit before running
 * the model.
 */
public record LoopIteration(boolean stop, Long currentRunId, RunVO currentRun, KernelContext ctx,
                            int currentRunStart, boolean hasToolCalls, List<RuntimeTool> tools,
                            List<ToolDefinitionDTO> toolDefs, boolean isLastLoop) {

    public static LoopIteration stopped() {
        return new LoopIteration(true, null, null, null, -1, false, List.of(), List.of(), false);
    }
}

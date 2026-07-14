package com.buukle.agent.runtime.kernel.contract;

public record CliExecutionBinding(
        String commandTemplate,
        String workingDir
) {
}

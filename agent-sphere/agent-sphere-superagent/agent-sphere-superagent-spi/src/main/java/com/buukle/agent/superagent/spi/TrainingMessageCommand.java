package com.buukle.agent.superagent.spi;

public record TrainingMessageCommand(
        String message,
        String runMode,
        Long modelRouteId,
        String reasoningEffort) {
}

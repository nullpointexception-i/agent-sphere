package com.buukle.agent.superagent.spi;

public record TrainingSessionCommand(
        Long superAgentId,
        Long workerInstanceId,
        Long tenantId,
        Long ownerUserId,
        String ownerUsername,
        String agentName) {
}

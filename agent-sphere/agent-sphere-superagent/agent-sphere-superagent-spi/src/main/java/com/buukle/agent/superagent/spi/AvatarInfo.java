package com.buukle.agent.superagent.spi;

/** The avatar fields the project assistant needs for directory, consultation, and dispatch. */
public record AvatarInfo(
        Long agentId,
        String name,
        Long ownerUserId,
        String status,
        boolean publicVisible,
        Long routerInstanceId,
        Long workerInstanceId
) {
}

package com.buukle.agent.superagent.spi;

/** Input of one synchronous avatar consultation. */
public record AvatarConsultCommand(
        Long tenantId,
        Long requesterUserId,
        String requesterUsername,
        Long avatarId,
        String question,
        long timeoutSeconds
) {
}

package com.buukle.agent.project.spi;

/** Answer submitted by the target member of a targeted project question. */
public record ProjectClarificationAnswerCommand(
        String clarificationId,
        Long answeringUserId,
        String answeringUsername,
        String response
) {
}

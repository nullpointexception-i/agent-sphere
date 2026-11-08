package com.buukle.agent.superagent.spi;

/**
 * Owner-only training chat bound directly to a Super-Agent Worker instance.
 *
 * <p>Implemented in orchestration because it needs the runtime chat service;
 * the super-agent service keeps the authorization and agent-to-worker mapping.
 */
public interface TrainingSessionPort {

    TrainingSessionInfo ensureSession(TrainingSessionCommand command);

    TrainingChatResult send(Long sessionId, TrainingMessageCommand command);

    void reset(Long sessionId);
}

package com.buukle.agent.superagent.spi;

/** Synchronous consultation with one avatar (a bounded, read-only side conversation). */
public interface AvatarConsultSpi {

    /**
     * Ask an avatar a question and wait for its answer.
     *
     * <p>Implementations must fail fast when the avatar is busy and must not start another
     * consultation from inside the avatar's own run (no recursion).
     */
    AvatarConsultResult consult(AvatarConsultCommand command);
}

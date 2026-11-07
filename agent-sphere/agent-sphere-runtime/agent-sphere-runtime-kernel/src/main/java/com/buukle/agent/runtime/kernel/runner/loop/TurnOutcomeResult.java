package com.buukle.agent.runtime.kernel.runner.loop;

/** Directive + updated loop state produced by one turn-outcome dispatch. */
public record TurnOutcomeResult(LoopAction action, int silentTurns, int currentRunStart) {
}

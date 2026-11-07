package com.buukle.agent.runtime.kernel.runner.loop;

/** Result of one COMPLETE-outcome handling: directive + updated silent-turn counter. */
public record CompleteTurnResult(LoopAction action, int silentTurns) {
}

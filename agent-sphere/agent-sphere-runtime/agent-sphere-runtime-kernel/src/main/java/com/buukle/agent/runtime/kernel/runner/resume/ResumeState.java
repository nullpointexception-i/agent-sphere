package com.buukle.agent.runtime.kernel.runner.resume;

/** Mutable counters/flags carried across the resume branches of one run. */
public final class ResumeState {

    public int loopCount;
    public int currentRunStart;
    public boolean hasToolCalls;
    public boolean toolsEverExecuted;

    public ResumeState(int loopCount, int currentRunStart, boolean hasToolCalls, boolean toolsEverExecuted) {
        this.loopCount = loopCount;
        this.currentRunStart = currentRunStart;
        this.hasToolCalls = hasToolCalls;
        this.toolsEverExecuted = toolsEverExecuted;
    }
}

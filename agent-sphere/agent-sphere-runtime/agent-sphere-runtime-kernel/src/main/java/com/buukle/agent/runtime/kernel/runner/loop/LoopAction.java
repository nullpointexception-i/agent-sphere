package com.buukle.agent.runtime.kernel.runner.loop;

/** What the loop should do after a turn outcome or resume step has been handled. */
public enum LoopAction {
    BREAK,
    CONTINUE,
    CONTINUE_WITH_INPUT,
    CONTINUE_TOOL_LOOP,
    PROCEED
}

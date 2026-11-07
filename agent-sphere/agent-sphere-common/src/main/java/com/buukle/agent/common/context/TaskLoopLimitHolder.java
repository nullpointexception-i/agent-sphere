package com.buukle.agent.common.context;

/**
 * Higher loop limit for task-triggered runs (propagated via ThreadLocal).
 * Set by the task scheduling thread and read by async execution (AsyncConfig) and SessionRunner; the
 * task thread must clear it when it finishes.
 * Uses InheritableThreadLocal: Java 21 virtual threads are run by whichever carrier thread the pool
 * picks, and Inheritable snapshots the task thread's value into the executing thread when the run is
 * submitted to the ExecutorService.
 */
public final class TaskLoopLimitHolder {

    private static final InheritableThreadLocal<Integer> LOOP_LIMIT = new InheritableThreadLocal<>();

    private TaskLoopLimitHolder() {
    }

    public static void set(Integer maxLoopCount) {
        LOOP_LIMIT.set(maxLoopCount);
    }

    public static Integer get() {
        return LOOP_LIMIT.get();
    }

    public static void clear() {
        LOOP_LIMIT.remove();
    }
}
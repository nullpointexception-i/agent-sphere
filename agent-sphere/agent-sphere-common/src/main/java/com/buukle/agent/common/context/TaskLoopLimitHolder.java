package com.buukle.agent.common.context;

/**
 * 任务触发 run 的更高轮次上限（ThreadLocal 传播）。
 * 任务调度线程 set，异步执行（AsyncConfig）与 SessionRunner 读取；任务线程结束必须 clear。
 * 使用 InheritableThreadLocal：Java 21 虚拟线程由 carrier 线程池任取执行，Inheritable
 * 在 run 提交给 ExecutorService 时把任务线程的 value 快照传给执行线程。
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
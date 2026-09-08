package com.buukle.agent.runtime.kernel.runner;

/**
 * run 循环上限解析：
 * <ul>
 *     <li>{@link #resolveMain}：主循环，优先级 实例 {@code INSTANCE} > 任务 {@code TASK} > 系统 {@code CHAT}；</li>
 *     <li>{@link #resolveSub}：子 Agent 循环，优先级 实例 {@code INSTANCE} > 技能配置 {@code CHAT}（不参与任务提额）。</li>
 * </ul>
 * 实例覆盖 {@code >0} 视为已配置（0 / null 视为未配置）。
 */
public final class LoopLimitResolver {

    private LoopLimitResolver() {
    }

    public record ResolvedLoopLimit(int limit, LoopLimitSource source) {
    }

    public static ResolvedLoopLimit resolveMain(Integer instanceLoopLimit, Integer taskLoopLimit, int defaultLimit) {
        if (instanceLoopLimit != null && instanceLoopLimit > 0) {
            return new ResolvedLoopLimit(instanceLoopLimit, LoopLimitSource.INSTANCE);
        }
        if (taskLoopLimit != null && taskLoopLimit > 0) {
            return new ResolvedLoopLimit(taskLoopLimit, LoopLimitSource.TASK);
        }
        return new ResolvedLoopLimit(defaultLimit, LoopLimitSource.CHAT);
    }

    public static ResolvedLoopLimit resolveSub(Integer instanceLoopLimit, int defaultLimit) {
        if (instanceLoopLimit != null && instanceLoopLimit > 0) {
            return new ResolvedLoopLimit(instanceLoopLimit, LoopLimitSource.INSTANCE);
        }
        return new ResolvedLoopLimit(defaultLimit, LoopLimitSource.CHAT);
    }
}
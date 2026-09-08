package com.buukle.agent.runtime.kernel.runner;

/**
 * run 循环上限的来源：
 * <ul>
 *     <li>{@link #INSTANCE}：实例级 {@code maxLoopCount} 覆盖（主/子循环均生效，最高优先）；</li>
 *     <li>{@link #TASK}：任务触发的 run 提额（仅主循环）；</li>
 *     <li>{@link #CHAT}：系统/技能配置兜底（主循环 runner.max-loop-count，子循环 skill.max-sub-loop-count）。</li>
 * </ul>
 */
public enum LoopLimitSource {
    INSTANCE,
    TASK,
    CHAT
}
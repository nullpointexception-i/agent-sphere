package com.buukle.agent.runtime.kernel.constants;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;

/**
 * 全局 wait 工具的常量。
 *
 * <p>wait 不走「睡 N 秒再返回」的常规工具语义，而是**挂起-恢复**：工具只写一条
 * {@code agent_pending_wait} 并立即返回，SessionRunner 判定后 break 退出循环，
 * 到点再由 {@code PendingWaitWakeScheduler} 唤醒。这样等待不占用虚拟线程、
 * 不占 maxParallel 槽位，也不受 tool.execution-timeout / step.execution-timeout 约束 ——
 * 后两者正是「等超过 60s」在常规工具语义下会被掐断的原因。
 *
 * <p>形态刻意照抄 {@link ChatClarification}（无独立 maven 模块、无 SPI bean）：
 * 常量 + 手写 schema + ContextPreparer 显式注册 + ToolExecutor 拦截。
 */
public final class WaitConstants {

    public static final String INTERNAL_NAME = "wait";
    public static final String DISPLAY_NAME = "Wait";
    public static final String DISPLAY_NAME_CN = "等待";

    public static final String TOOL_NAME = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.WAIT.getId();

    /** 工具返回的 status；SessionRunner 循环外靠它判定「本次应挂起而非继续回模型」。 */
    public static final String STATUS_WAITING = "waiting";

    public static final String JSON_STATUS = "status";
    public static final String JSON_WAIT_MS = "wait_ms";

    /**
     * 单次 wait 上限（10 分钟）。挂起本身不受 tool/step 超时约束，但模型可以反复挂起，
     * 没有上限就等于给了无限长的空转窗口。
     */
    public static final int MAX_WAIT_MS = 600_000;

    public static final int MIN_WAIT_MS = 100;

    /**
     * 唤醒时喂回模型的前缀。
     *
     * <p>措辞上的重点是**禁止把「等过了」当成「已就绪」**：等待只给了页面时间，
     * 不保证任何状态变化。若模型醒来直接假设渲染完成就会基于陈旧页面做动作，
     * 这正是 getContent(mode:ready) 一套反复强调「等完必须复核」的同一条经验。
     */
    public static final String WAIT_RESUME_PREFIX =
            "[Waited the requested time. The page had time to settle but you do NOT know its current state — "
                    + "re-read it with getContent before acting; do NOT assume the wait changed anything, "
                    + "and do NOT re-issue the same wait]: ";

    public static final String DESCRIPTION =
            "Pause this run for a fixed number of milliseconds, then continue automatically. The run is suspended "
                    + "immediately (no thread or browser action is held); when the time elapses the run resumes and "
                    + "you receive the elapsed result. Use it ONLY to give dynamic content time to appear or settle "
                    + "(dropdown flyouts, re-renders, async result counts). NEVER use it as a substitute for waiting "
                    + "for a specific condition — for that use getContent(mode=ready, selectors:[...], waitMs) and "
                    + "check `ready`. Waiting never guarantees the page is ready: after waking you MUST re-read the "
                    + "current state before acting, and must NOT repeat the same wait if it already elapsed.";

    /** agent_pending_wait.status 落库值。 */
    public static final String PENDING_STATUS = "PENDING";
    public static final String RESUMED_STATUS = "RESUMED";
    /** run 被停止时取消挂起：置此状态后，waker（listDue/claimForResume 只认 PENDING）不再恢复该 run。 */
    public static final String CANCELLED_STATUS = "CANCELLED";
    /** 恢复重试超限：置此状态并发 run 终态事件，避免永久卡 WAITING。 */
    public static final String FAILED_STATUS = "FAILED";

    /** 挂起类型：wait 内置工具（唤醒时补工具结果 + 追加合成 user）。 */
    public static final String KIND_WAIT = com.buukle.agent.common.constant.PendingWaitKind.WAIT;
    /** 挂起类型：LLM 瞬时失败后的梯度重试（唤醒时不补工具结果、不追加合成 user）。 */
    public static final String KIND_LLM_RETRY = com.buukle.agent.common.constant.PendingWaitKind.LLM_RETRY;

    /** 单条等待记录的恢复重试上限（cron 每 10s 一轮，约 50s 内重试 5 次）。 */
    public static final int MAX_RESUME_ATTEMPTS = 5;

    private WaitConstants() {
    }
}
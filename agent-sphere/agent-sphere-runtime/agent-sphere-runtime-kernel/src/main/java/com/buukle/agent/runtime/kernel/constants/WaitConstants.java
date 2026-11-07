package com.buukle.agent.runtime.kernel.constants;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.instance.dtvo.enums.InstanceCapabilityEnum;

/**
 * Constants for the global wait tool.
 *
 * <p>wait does not follow the usual "sleep N seconds then return" tool semantics; it is **suspend-resume**: the tool writes only one
 * {@code agent_pending_wait} row and returns immediately, then SessionRunner decides and breaks out of the loop,
 * and {@code PendingWaitWakeScheduler} wakes the run when due. This way the wait occupies no virtual thread,
 * no maxParallel slot, and is not bound by tool.execution-timeout / step.execution-timeout —
 * those two are exactly why "waiting more than 60s" gets cut off under normal tool semantics.
 *
 * <p>The shape deliberately follows {@link ChatClarification} (no separate Maven module, no SPI bean):
 * constants + hand-written schema + explicit registration in ContextPreparer + interception in ToolExecutor.
 */
public final class WaitConstants {

    public static final String INTERNAL_NAME = "wait";
    public static final String DISPLAY_NAME = "Wait";
    public static final String DISPLAY_NAME_CN = "等待";

    public static final String TOOL_NAME = InstanceCapabilityEnum.LLM_PREFIX_BUILTIN + BuiltinToolEnum.WAIT.getId();

    /** Status returned by the tool; outside the loop SessionRunner uses it to decide "this turn should suspend rather than go back to the model". */
    public static final String STATUS_WAITING = "waiting";

    public static final String JSON_STATUS = "status";
    public static final String JSON_WAIT_MS = "wait_ms";

    /**
     * Single wait cap (10 minutes). Suspension itself is not bound by tool/step timeouts, but the model can suspend repeatedly,
     * and without a cap that would hand it an unbounded idle window.
     */
    public static final int MAX_WAIT_MS = 600_000;

    public static final int MIN_WAIT_MS = 100;

    /**
     * Prefix fed back to the model on wake.
     *
     * <p>The wording matters: **never treat "waited" as "ready"**: waiting only gives the page time,
     * it guarantees no state change. If the model wakes up and assumes rendering finished, it will act on a stale page —
     * the same lesson repeatedly emphasized by the getContent(mode:ready) flow: "always re-check after waiting".
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

    /** Value persisted in agent_pending_wait.status. */
    public static final String PENDING_STATUS = "PENDING";
    public static final String RESUMED_STATUS = "RESUMED";
    /** Cancel a suspension when the run is stopped: once set, the waker (listDue/claimForResume only accept PENDING) will not resume the run. */
    public static final String CANCELLED_STATUS = "CANCELLED";
    /** Resume retry limit exceeded: set this status and emit the run terminal event so the run is not stuck in WAITING forever. */
    public static final String FAILED_STATUS = "FAILED";

    /** Suspension kind: the wait builtin tool (on wake, append the tool result + a synthetic user message). */
    public static final String KIND_WAIT = com.buukle.agent.common.constant.PendingWaitKind.WAIT;
    /** Suspension kind: graded retry after a transient LLM failure (on wake, no tool result and no synthetic user message). */
    public static final String KIND_LLM_RETRY = com.buukle.agent.common.constant.PendingWaitKind.LLM_RETRY;

    /** Resume retry limit for a single wait record (the cron runs every 10s, so about 5 retries within ~50s). */
    public static final int MAX_RESUME_ATTEMPTS = 5;

    private WaitConstants() {
    }
}
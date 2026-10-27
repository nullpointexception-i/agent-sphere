package com.buukle.agent.model.dtvo.complete;

/**
 * 流式响应的终止原因（OpenAI 兼容的 {@code finish_reason}）。
 *
 * <p>放在 model-dtvo：provider 侧（model-service）负责解析并透传，消费侧
 * （runtime-kernel 的 SessionRunner）负责判定，两边都要用，而 model-service 不依赖 kernel。
 *
 * <p><b>为什么必须区分</b>：过去在流结束时一律上报 {@code stop}，于是
 * {@link #LENGTH}（被 max_tokens 截断）和 {@link #CONTENT_FILTER}（被安全策略拦截）
 * 与「模型主动说完」无法区分 —— 半截答案被当成正常完成，run 置 COMPLETED，属于静默失败。
 */
public final class FinishReason {

    /** 模型主动结束（正常完成）。 */
    public static final String STOP = "stop";

    /** 输出被 max_tokens 截断：内容不完整，不能当完成。 */
    public static final String LENGTH = "length";

    /** 输出被安全策略拦截：同样不是完成。 */
    public static final String CONTENT_FILTER = "content_filter";

    /** 本轮以工具调用结束（正常工具轮）。 */
    public static final String TOOL_CALLS = "tool_calls";

    private FinishReason() {
    }
}

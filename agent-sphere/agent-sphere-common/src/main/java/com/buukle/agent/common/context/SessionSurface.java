package com.buukle.agent.common.context;

/**
 * 会话操作的来源端。
 *
 * <p>同一个会话接口（建会话 / 发消息）被多个入口共用，而实例上可以按来源分别控制是否放行
 * （{@code agent_instance.allow_console_session} / {@code allow_widget_session}），
 * 因此服务端必须知道这次调用来自哪一端。
 */
public enum SessionSurface {

    /** 嵌入式 widget（agent-sphere-copilot-widget）。 */
    WIDGET,

    /** 主站控制台（agent-sphere-ui）。 */
    CONSOLE,

    /**
     * 服务端内部调用 —— 任务提交、结构化提炼等。
     *
     * <p>这是**未设置来源时的默认值**：内部调用不经过 HTTP 拦截器（`/api/v1/api/*` 提前放行，
     * `pollSweep` / 提炼轮干脆没有请求线程），拿到 null 必须按内部处理，否则任务路径会被自己的
     * 开关拦掉。
     */
    INTERNAL
}
